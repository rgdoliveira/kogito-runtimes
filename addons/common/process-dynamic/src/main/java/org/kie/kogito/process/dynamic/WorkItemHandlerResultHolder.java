/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.process.dynamic;

import java.util.Map;

import org.kogito.workitem.rest.resulthandlers.RestWorkItemHandlerResult;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;

public class WorkItemHandlerResultHolder implements RestWorkItemHandlerResult {

    private Map<String, Object> result;

    @Override
    public Object apply(HttpResponse<Buffer> buffer, Class<?> clazz) {
        result = buffer.bodyAsJson(Map.class);
        return result;
    }

    public Map<String, Object> getResult() {
        return result;
    }
}
