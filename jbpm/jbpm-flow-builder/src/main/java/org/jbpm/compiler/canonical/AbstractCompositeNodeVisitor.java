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
package org.jbpm.compiler.canonical;

import org.jbpm.compiler.canonical.node.NodeVisitorBuilderService;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.workflow.core.node.CompositeContextNode;
import org.kie.api.definition.process.Node;

import com.github.javaparser.ast.stmt.BlockStmt;

public abstract class AbstractCompositeNodeVisitor<T extends CompositeContextNode> extends AbstractNodeVisitor<T> {

    protected NodeVisitorBuilderService nodevisitorService;

    public AbstractCompositeNodeVisitor(ClassLoader classLoader) {
        super(classLoader);
        this.nodevisitorService = new NodeVisitorBuilderService(classLoader);
    }

    protected <U extends Node> void visitNodes(String factoryField, U[] nodes, BlockStmt body, VariableScope variableScope, ProcessMetaData metadata) {
        for (U node : nodes) {
            AbstractNodeVisitor<U> visitor = (AbstractNodeVisitor<U>) nodevisitorService.findNodeVisitor(node.getClass());
            if (visitor == null) {
                continue;
            }
            visitor.visitNode(factoryField, node, body, variableScope, metadata);
        }
    }

    protected String stripExpression(String expression) {
        if (expression.startsWith("#{")) {
            return expression.substring(2, expression.length() - 1);
        }
        return expression;
    }

}
