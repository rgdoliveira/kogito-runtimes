/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.kogito.addons.quarkus.knative.eventing.deployment;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.jandex.DotName;
import org.kie.kogito.addons.quarkus.knative.eventing.KSinkInjectionHealthCheck;
import org.kie.kogito.addons.quarkus.knative.eventing.deployment.resources.KogitoSource;
import org.kie.kogito.addons.quarkus.knative.eventing.deployment.resources.KogitoSourceSpec;
import org.kie.kogito.event.EventKind;
import org.kie.kogito.quarkus.addons.common.deployment.AnyEngineKogitoAddOnProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.knative.eventing.v1.Broker;
import io.fabric8.knative.eventing.v1.BrokerBuilder;
import io.fabric8.knative.eventing.v1.Trigger;
import io.fabric8.knative.eventing.v1.TriggerBuilder;
import io.fabric8.knative.internal.pkg.apis.duck.v1.DestinationBuilder;
import io.fabric8.knative.internal.pkg.tracker.ReferenceBuilder;
import io.fabric8.knative.sources.v1.SinkBinding;
import io.fabric8.knative.sources.v1.SinkBindingBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsTest;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceBuildItem;
import io.quarkus.deployment.builditem.SystemPropertyBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;

import static org.kie.kogito.addons.quarkus.knative.eventing.KnativeEventingConfigSourceFactory.INCLUDE_PROCESS_EVENTS;

public class KogitoAddOnKnativeEventingProcessor extends AnyEngineKogitoAddOnProcessor {

    private static final String FEATURE = "kogito-addon-knative-eventing-extension";
    /**
     * Name of the generated file
     */
    private static final String FILE_NAME = "kogito.yml";
    /**
     * Target directory where to fetch the generated kubernetes.yml file and where to generate ours
     */
    private static final String TARGET_KUBERNETES = "kubernetes";

    private static final Logger LOGGER = LoggerFactory.getLogger(KogitoAddOnKnativeEventingProcessor.class);

    public static final DotName PROCESS_EVENTS_PUBLISHER_CLASS = DotName.createSimple("org.kie.kogito.events.process.ReactiveMessagingEventPublisher");

    EventingConfiguration config;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIfNot = { IsDevelopment.class })
    HealthBuildItem registerKSinkInjectionHealthCheck(Optional<KogitoKnativeResourcesMetadataBuildItem> resourcesMetadata) {
        boolean hasProducedEvents = resourcesMetadata.map(KogitoAddOnKnativeEventingProcessor::hasProducedEvents).orElse(false);
        return new HealthBuildItem(KSinkInjectionHealthCheck.class.getName(), hasProducedEvents);
    }

    /**
     * Build step for the actual Kogito Knative resources generation.
     * <p>
     * It only runs if:
     * 1) The current build is not a test environment.
     * This conditional was borrowed from the original `KubernetesProcessor` build processor.
     * 2) The current build is not in dev mode environment.
     * This conditional was added in favor of the kogito serverless workflow operator dev mode image, since in dev
     * mode no Broker, nor Sink Binding is generated.
     *
     * @param outputTarget Build item created by the quarkus deployment infrastructure with information about the current output target directory to generate the files
     * @param resourcesMetadata Build item generated by other processors (e.g. {@link KogitoProcessKnativeEventingProcessor})
     * @param generatedResources outcome of this build step with the contents of the `kogito.yml` file
     */
    @BuildStep(onlyIfNot = { IsTest.class, IsDevelopment.class })
    void generate(OutputTargetBuildItem outputTarget,
            Optional<KogitoKnativeResourcesMetadataBuildItem> resourcesMetadata,
            BuildProducer<GeneratedFileSystemResourceBuildItem> generatedResources) {
        if (resourcesMetadata.isPresent()) {
            final List<Trigger> triggers = this.generateTriggers(resourcesMetadata.get());
            final Optional<KogitoSource> kogitoSource = this.generateKogitoSource(resourcesMetadata.get());
            Optional<SinkBinding> sinkBinding = Optional.empty();
            if (kogitoSource.isEmpty()) {
                sinkBinding = this.generateSinkBinding(resourcesMetadata.get());
            }

            if (sinkBinding.isPresent() || kogitoSource.isPresent() || !triggers.isEmpty()) {
                final Optional<Broker> broker = this.generateBroker();
                final Path outputDir = outputTarget.getOutputDirectory().resolve(TARGET_KUBERNETES);
                final byte[] resourcesBytes =
                        new KogitoKnativeGenerator()
                                .addResources(triggers)
                                .addOptionalResource(kogitoSource)
                                .addOptionalResource(sinkBinding)
                                .addOptionalResource(broker)
                                .getResourcesBytes();
                if (resourcesBytes == null || resourcesBytes.length == 0) {
                    LOGGER.warn("Couldn't generate Kogito Knative resources for service {}", resourcesMetadata.get().getDeployment().getName());
                } else {
                    generatedResources.produce(new GeneratedFileSystemResourceBuildItem(Path.of(TARGET_KUBERNETES, FILE_NAME).toString(), resourcesBytes));
                    LOGGER.info("Generated Knative resources for Kogito Service {} in {}", resourcesMetadata.get().getDeployment().getName(), outputDir.resolve(FILE_NAME));
                }
            } else {
                LOGGER.info("No events found in the Kogito resources defined in the project. Skipping Kogito Knative resources generation.");
            }
        }
    }

    @BuildStep
    void checkProcessEvents(BuildProducer<SystemPropertyBuildItem> systemProperties, CombinedIndexBuildItem indexBuildItem) {
        if (indexBuildItem.getIndex().getClassByName(PROCESS_EVENTS_PUBLISHER_CLASS) != null) {
            systemProperties.produce(new SystemPropertyBuildItem(INCLUDE_PROCESS_EVENTS, "true"));
        }
    }

    private Optional<Broker> generateBroker() {
        if (config.autoGenerateBroker) {
            LOGGER.warn("Generating in memory Knative Broke. Note that this Broker is not meant for production usage!");
            return Optional.of(new BrokerBuilder().withNewMetadata()
                    .withName(SinkConfiguration.DEFAULT_SINK_NAME)
                    .endMetadata().build());
        }
        return Optional.empty();
    }

    private Optional<KogitoSource> generateKogitoSource(KogitoKnativeResourcesMetadataBuildItem metadata) {
        if (config.generateKogitoSource) {
            final KogitoSource kogitoSource = new KogitoSource();
            final KogitoSourceSpec spec = new KogitoSourceSpec();
            final ObjectMeta objectMeta = new ObjectMeta();
            objectMeta.setName(metadata.getDeployment().getName());
            spec.setSink(new DestinationBuilder().withNewRef()
                    .withName(config.sink.name)
                    .withApiVersion(config.sink.apiVersion)
                    .withKind(config.sink.kind)
                    .withNamespace(config.sink.namespace.orElse("")).endRef().build());
            spec.setSubject(new ReferenceBuilder()
                    .withName(metadata.getDeployment().getName())
                    .withKind(metadata.getDeployment().getKind())
                    .withApiVersion(metadata.getDeployment().getApiVersion()).build());
            kogitoSource.setMetadata(objectMeta);
            kogitoSource.setSpec(spec);
            return Optional.of(kogitoSource);
        }
        return Optional.empty();
    }

    private Optional<SinkBinding> generateSinkBinding(KogitoKnativeResourcesMetadataBuildItem metadata) {
        if (hasProducedEvents(metadata)) {
            return Optional.of(new SinkBindingBuilder()
                    .withNewMetadata().withName(KnativeResourcesUtil.generateSinkBindingName(metadata.getDeployment().getName())).endMetadata()
                    .withNewSpec()
                    .withNewSubject()
                    .withName(metadata.getDeployment().getName())
                    .withKind(metadata.getDeployment().getKind())
                    .withApiVersion(metadata.getDeployment().getApiVersion())
                    .endSubject()
                    .withNewSink().withNewRef()
                    .withName(config.sink.name) // from properties
                    .withApiVersion(config.sink.apiVersion)
                    .withKind(config.sink.kind)
                    .withNamespace(config.sink.namespace.orElse(""))
                    .endRef().endSink().endSpec()
                    .build());
        }
        return Optional.empty();
    }

    private List<Trigger> generateTriggers(KogitoKnativeResourcesMetadataBuildItem metadata) {
        return metadata.getCloudEvents().stream()
                .filter(ce -> ce.getKind() == EventKind.CONSUMED)
                .map(ce -> new TriggerBuilder()
                        .withNewMetadata()
                        .withName(KnativeResourcesUtil.generateTriggerName(ce.getType(), metadata.getDeployment().getName()))
                        .endMetadata()
                        .withNewSpec()
                        .withNewFilter()
                        .addToAttributes(Collections.singletonMap("type", ce.getType()))
                        .endFilter()
                        .withBroker(config.broker)
                        .withNewSubscriber()
                        .withNewRef()
                        .withKind(metadata.getDeployment().getKind())
                        .withName(metadata.getDeployment().getName())
                        .withApiVersion(metadata.getDeployment().getApiVersion())
                        .endRef().endSubscriber().endSpec()
                        .build())
                .collect(Collectors.toList());
    }

    private static boolean hasProducedEvents(KogitoKnativeResourcesMetadataBuildItem metadata) {
        return metadata.getCloudEvents().stream().anyMatch(ce -> ce.getKind() == EventKind.PRODUCED);
    }
}
