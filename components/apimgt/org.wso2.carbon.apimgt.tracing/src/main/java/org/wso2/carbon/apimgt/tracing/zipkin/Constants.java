/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.tracing.zipkin;

/**
 * ZIPKIN dependent constants
 */

public class Constants {

    static final String CONFIG_PORT = "OpenTracer.Port";
    static final String CONFIG_HOST = "OpenTracer.Hostname";
    static final String CONFIG_API_CONTEXT = "OpenTracer.APIContext";

    static final int DEFAULT_PORT = 9411;
    static final String DEFAULT_HOST = "localhost";
    static final String DEFAULT_API_CONTEXT = "/api/v2/spans";

}
