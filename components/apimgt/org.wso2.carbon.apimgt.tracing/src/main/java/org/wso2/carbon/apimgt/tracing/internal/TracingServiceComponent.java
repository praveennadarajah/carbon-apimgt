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

package org.wso2.carbon.apimgt.tracing.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.apimgt.tracing.TracingService;
import org.wso2.carbon.apimgt.tracing.TracingServiceImpl;

/**
 * @scr.component name="org.wso2.carbon.apimgt.tracing.internal.TracingServiceComponent" immediate="true"
 */

public class TracingServiceComponent {

    private static final Log log = LogFactory.getLog(TracingServiceComponent.class);

    protected void activate(ComponentContext componentContext) {

        try {
            log.debug("Tracing Component activated");
            BundleContext bundleContext = componentContext.getBundleContext();
            bundleContext.registerService(TracingService.class, new TracingServiceImpl(), null);

        } catch (Throwable t) {
            log.error("Error occured in tracing component activation", t);
        }
    }

    protected void deactivate(ComponentContext componentContext) {

        log.debug("Tracing Component deactivated");
    }

}
