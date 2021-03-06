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
package org.apache.sling.resourceresolver.impl.helper;

/**
 * The <code>FeaturesHolder</code> interface is an API to provide dynamic
 * access to the Features service without having to hold on to the
 * Features instance directly.
 */
public interface FeaturesHolder {

    /** This holder always returns null */
    FeaturesHolder EMPTY_HOLDER = new FeaturesHolder() {

        public Object getFeatures() {
            return null;
        }
    };

    /**
     * @return The {@code Features} service if available, {@code null} otherwise
     */
    public Object getFeatures();

}
