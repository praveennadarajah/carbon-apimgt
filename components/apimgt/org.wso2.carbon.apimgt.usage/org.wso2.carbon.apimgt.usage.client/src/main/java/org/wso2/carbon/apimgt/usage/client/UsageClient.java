/*
* Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/
package org.wso2.carbon.apimgt.usage.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIStatus;
import org.wso2.carbon.apimgt.api.model.Provider;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.client.dto.APIListDTO;
import org.wso2.carbon.apimgt.usage.client.dto.ApisByTimeDTO;
import org.wso2.carbon.apimgt.usage.client.dto.DeveloperListDTO;
import org.wso2.carbon.apimgt.usage.client.exception.APIMgtUsageQueryServiceClientException;
import org.wso2.carbon.apimgt.usage.client.pojo.SubscriberCountByAPIs;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * usageClient class it use to expose the Statistic class instance. it responsible to make instance of the class that is provided by the api-manager.xml
 */
public class UsageClient {
    private static final Log log = LogFactory.getLog(UsageClient.class);
    private static APIUsageStatisticsClient usageStatisticsClient;

    /**
     * central point to initialise datasources or related configuration done by the admin-dashboard analytics section
     *
     * @throws APIMgtUsageQueryServiceClientException
     */
    public static void initializeDataSource() throws APIMgtUsageQueryServiceClientException {
        APIUsageStatisticsClient client = UsageClient.getStatisticClient(null);
        client.initializeDataSource();
    }

    /**
     * central public method used to get the instance if the statistic client
     *
     * @return return the APIUsageStatisticsClient implementation
     * @throws APIMgtUsageQueryServiceClientException if error in creating instance
     */
    public static APIUsageStatisticsClient getClient(String user) throws APIMgtUsageQueryServiceClientException {
        if (isDataPublishingEnabled()) {
            return UsageClient.getStatisticClient(user);
        } else {
            return null;
        }
    }

    /**
     * Use to check whether analytics is enabled
     *
     * @return return boolean value indicating whether analytics enable
     */
    public static boolean isDataPublishingEnabled() {
        return APIUtil.isAnalyticsEnabled();
    }

    /**
     * Use to get instance of implementation class of the APIUsageStatisticsClient that is defined in the apim-manager.xml
     *
     * @return instance of a APIUsageStatisticsClient
     * @throws APIMgtUsageQueryServiceClientException throws if instantiation problem occur
     */
    private static APIUsageStatisticsClient getStatisticClient(String user)
            throws APIMgtUsageQueryServiceClientException {

        //read the api-manager.xml and get the Statistics class name
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService()
                .getAPIManagerConfiguration();
        String className = config.getFirstProperty(APIConstants.STAT_PROVIDER_IMPL);

        try {
            //get the Class from the class name
            Class statClass = APIUtil.getClassForName(className);
            //use the constructor and pass appropriate args to get a instance
            if (user != null) {
                usageStatisticsClient = (APIUsageStatisticsClient) statClass.getConstructor(String.class)
                        .newInstance(user);
            } else {
                usageStatisticsClient = (APIUsageStatisticsClient) statClass.getConstructor().newInstance();
            }
        } catch (InstantiationException e) {
            throw new APIMgtUsageQueryServiceClientException("Cannot instantiate Statistic Client class: " + className,
                    e);
        } catch (IllegalAccessException e) {
            throw new APIMgtUsageQueryServiceClientException(
                    "Cannot access the constructor in Statistic Client class: " + className, e);
        } catch (InvocationTargetException e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while getting constructor", e);
        } catch (NoSuchMethodException e) {
            throw new APIMgtUsageQueryServiceClientException(
                    "Cannot found expected constructor in Statistic Client class: " + className, e);
        } catch (ClassNotFoundException e) {
            throw new APIMgtUsageQueryServiceClientException("Cannot found the Statistic Client class: " + className,
                    e);
        }
        return usageStatisticsClient;
    }

    /**
     * Get the Subscriber count and information related to the APIs
     *
     * @param loggedUser user of the current session
     * @return return list of SubscriberCountByAPIs objects. which contain the list of apis and related subscriber counts
     * @throws APIManagementException throws exception if error occur
     */
    public static List<SubscriberCountByAPIs> getSubscriberCountByAPIs(String loggedUser, boolean isAllStatistics)
            throws APIManagementException {

        APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(loggedUser);
        String providerName = null;
        if (isAllStatistics) {
            providerName = "__all_providers__";
        } else {
            providerName = loggedUser;
        }

        List<SubscriberCountByAPIs> list = new ArrayList<SubscriberCountByAPIs>();
        boolean isTenantFlowStarted = false;
        try {
            loggedUser = APIUtil.replaceEmailDomain(loggedUser);
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(loggedUser));
            if (tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                PrivilegedCarbonContext.startTenantFlow();
                isTenantFlowStarted = true;
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

            if (providerName != null) {
                List<API> apiSet;
                //get the apis
                if (providerName.equals("__all_providers__")) {
                    apiSet = apiProvider.getAllAPIs();
                } else {
                    apiSet = apiProvider.getAPIsByProvider(APIUtil.replaceEmailDomain(loggedUser));
                }
                //iterate over apis
                for (API api : apiSet) {
                    //ignore created apis
                    if (api.getStatus() == APIStatus.CREATED) {
                        continue;
                    }
                    //ignore 0 counts
                    long count = apiProvider.getAPISubscriptionCountByAPI(api.getId());
                    if (count == 0) {
                        continue;
                    }
                    SubscriberCountByAPIs apiSub = new SubscriberCountByAPIs();
                    List<String> apiName = new ArrayList<String>();
                    apiName.add(api.getId().getApiName());
                    apiName.add(api.getId().getVersion());
                    apiName.add(api.getId().getProviderName());

                    apiSub.setCount(count);
                    apiSub.setApiName(apiName);
                    list.add(apiSub);
                }
            }
        } finally {
            if (isTenantFlowStarted) {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
        return list;
    }

    /**
     * getting the configured the statistics client type
     *
     * @return string value indicating type
     */
    public static String getStatClientType() {
        String type = null;
        try {
            type = UsageClient.getStatisticClient(null).getClientType();
        } catch (APIMgtUsageQueryServiceClientException e) {
            //throw new APIMgtUsageQueryServiceClientException("Error getting Statistics usage client instance", e);
            log.warn("Error getting usage statistic client...");
        }

        return type;
    }

    /**
     * Return list of developer sign ups over time
     *
     * @param apiName   - Name of th API
     * @param provider  - Provider of the API
     * @param apiFilter - API stat type
     * @param fromDate  - Start date of the time span
     * @param toDate    - End date of time span
     * @param limit     - limit of the results
     * @return List of count per user Agent
     * @throws APIMgtUsageQueryServiceClientException
     */
    public static List<DevelopersByTimeDTO> getDeveloperSignUpsOverTime(String apiName, String provider,
            String apiFilter, String fromDate, String toDate, int limit) throws APIMgtUsageQueryServiceClientException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            int tenantId = APIUtil.getTenantId(provider);
            String select = "select count(sub.subscriber_id) as y, sub.created_time as x ";
            String from;
            String where = "where sub.tenant_id=" + tenantId;
            String groupAndOrder = " group by sub.created_time order by sub.created_time asc";
            String time = " and sub.created_time between '" + fromDate + "' and '" + toDate + "' ";

            if ("All".equals(apiName)) {
                from = "from AM_SUBSCRIBER sub ";
            } else {
                from = "from AM_API as api,AM_APPLICATION AS app,AM_SUBSCRIBER sub, AM_SUBSCRIPTION as subc ";
                where += " and api.api_id=subc.api_id and app.application_id=subc.application_id and "
                        + "sub.subscriber_id=app.subscriber_id and api.api_name='" + apiName + "'";
                if (!"allAPIs".equals(apiFilter)) {
                    where += " and api.api_provider = '" + provider + "' ";
                }
            }

            String query = select + from + where + time + groupAndOrder;
            statement = connection.prepareStatement(query);
            //execute
            rs = statement.executeQuery();
            List<DevelopersByTimeDTO> list = new ArrayList<DevelopersByTimeDTO>();
            long x, y = 0;
            //iterate over the results
            while (rs.next()) {
                x = rs.getTimestamp("x").getTime();
                y += rs.getLong("y");
                list.add(new DevelopersByTimeDTO(x, y));
            }
            return list;

        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {

                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {

                }
            }
        }
    }

    /**
     * Return list of developer Application Registrations over time
     *
     * @param apiName   - Name of th API
     * @param provider  - API provider username
     * @param developer - Application developer
     * @param apiFilter - API stat type
     * @param fromDate  - Start date of the time span
     * @param toDate    - End date of time span
     * @param limit     - limit of the results
     * @return
     * @throws APIMgtUsageQueryServiceClientException
     */
    public static List<AppRegistrationDTO> getApplicationRegistrationOverTime(String apiName, String provider,
            String developer, String apiFilter, String fromDate, String toDate, int limit)
            throws APIMgtUsageQueryServiceClientException {

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;

        try {
            //get the connection
            connection = APIMgtDBUtil.getConnection();
            int tenantId = APIUtil.getTenantId(provider);
            String select = "SELECT count( app.application_id) as y, app.created_time as x ";
            String from;
            String where = "where sub.subscriber_id=app.subscriber_id and sub.tenant_id=" + tenantId;
            String groupAndOrder = " group by app.created_time order by app.created_time asc";
            String time = " and app.created_time between '" + fromDate + "' and '" + toDate + "' ";

            if ("All".equals(apiName)) {
                from = "from AM_APPLICATION AS app,AM_SUBSCRIBER sub ";
            } else {
                from = "from AM_API as api,AM_APPLICATION AS app,AM_SUBSCRIBER sub, AM_SUBSCRIPTION as subc ";
                where += " and api.api_id=subc.api_id and app.application_id=subc.application_id";
                where += " and api.api_name='" + apiName + "'";
                /*if (!"allAPIs".equals(apiFilter)) {
                    where += " and api.api_provider = '" + provider + "' ";
                } else {
                    APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(provider);
                    List<API> apiList = apiProvider.getAllAPIs();
                    StringBuilder apis = new StringBuilder(" and api.context in (");
                    if (apiList.size() > 0) {
                        apis.append("'").append(apiList.get(0).getContext()).append("'");
                    }
                    for (int i = 1; i < apiList.size(); i++) {
                        apis.append(", '").append(apiList.get(i).getContext()).append("' ");
                    }
                    apis.append(") ");
                    where += apis.toString();
                }*/
            }

            if (!"All".equals(developer)) {
                where += " and sub.user_id='" + developer + "'";
            }
            String query = select + from + where + time + groupAndOrder;
            statement = connection.prepareStatement(query);

            //execute
            rs = statement.executeQuery();

            List<AppRegistrationDTO> list = new ArrayList<AppRegistrationDTO>();
            long x, y = 0;
            //iterate over the results
            while (rs.next()) {
                x = rs.getTimestamp("x").getTime();
                y += rs.getLong("y");
                list.add(new AppRegistrationDTO(x, y));
            }
            return list;
        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {
                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {

                }
            }
        }
    }

    /**
     * Return list of API Subscriptions per applications over time
     *
     * @param apiName   - Name of th API
     * @param provider  - API provider username
     * @param apiFilter - API stat type
     * @param fromDate  - Start date of the time span
     * @param toDate    - End date of time span
     * @param limit     - limit of the results     *
     * @return List of count per user Agent
     * @throws APIMgtUsageQueryServiceClientException
     */
    public static List<SubscriptionOverTimeDTO> getAPISubscriptionsPerApp(String apiName, String provider,
            String apiFilter, String fromDate, String toDate, int limit)
            throws SQLException, APIMgtUsageQueryServiceClientException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            //get the connection
            connection = APIMgtDBUtil.getConnection();

            String select = "select count(subc.subscription_id) as subscription_count, subc.created_time as "
                    + "created_time,api.api_name, api.api_version ";
            String from = "from AM_API as api,  AM_SUBSCRIPTION as subc ";
            String where = "where api.api_id=subc.api_id ";
            String groupAndOrder = "group by subc.created_time order by subc.created_time asc ";
            String time = " and subc.created_time between '" + fromDate + "' and '" + toDate + "' ";
            if (!"allAPIs".equals(apiFilter)) {
                where += " and api.api_provider = '" + provider + "' ";
            } else {
                APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(provider);
                Object[] providerList = apiProvider.getAllProviders().toArray();
                StringBuilder providers = new StringBuilder(" and api.api_provider in (");
                if (providerList.length > 0) {
                    providers.append("'").append(((Provider) providerList[0]).getName()).append("'");
                }
                for (int i = 1; i < providerList.length; i++) {
                    providers.append(", '").append(((Provider) providerList[i]).getName()).append("' ");
                }
                providers.append(") ");
                where += providers.toString();
            }

            if (apiName != null) {
                where += "and api.api_name='" + apiName + "' ";
            }
            String query = select + from + where + time + groupAndOrder;
            statement = connection.prepareStatement(query);
            //execute
            rs = statement.executeQuery();
            List<SubscriptionOverTimeDTO> list = new ArrayList<SubscriptionOverTimeDTO>();
            long x;
            int subscription_count = 0;
            //iterate over the results
            while (rs.next()) {
                subscription_count += rs.getInt("subscription_count");
                long created_time = rs.getTimestamp("created_time").getTime();
                String api_name = rs.getString("api_name");
                String api_version = rs.getString("api_version");
                list.add(new SubscriptionOverTimeDTO(subscription_count, created_time, api_name, api_version));
            }
            return list;
        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {

                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {

                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {

                }
            }
        }
    }

    /**
     * get published api accumulated count over time
     *
     * @param provider  logged publisher
     * @param developer application developer
     * @param apiFilter api filter state
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the result
     * @return list of api count over time
     * @throws APIMgtUsageQueryServiceClientException throws if any db exception occured
     */
    public static List<ApisByTimeDTO> getApisByTime(String provider, String developer, String apiFilter,
            String fromDate, String toDate, int limit) throws APIMgtUsageQueryServiceClientException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            //get the connection
            connection = APIMgtDBUtil.getConnection();

            String query = "select count(api.api_id) as y, api.created_time as x from AM_API as api, AM_APPLICATION "
                    + "as app,AM_SUBSCRIBER sub, AM_SUBSCRIPTION as subc ";
            String group = "group by api.created_time,api.api_id ";
            String order = " order by api.created_time asc ";
            String where = "where api.api_id=subc.api_id and app.application_id=subc.application_id and "
                    + "sub.subscriber_id=app.subscriber_id and api.created_time between '" + fromDate + "' and '" +
                    toDate + "' ";

            if (!"All".equals(developer)) {
                where += " and subc.created_by = '" + developer + "' ";
            }
            if (!"allAPIs".equals(apiFilter)) {
                where += " and api.api_provider = '" + provider + "' ";
            } else {
                APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(provider);
                List<API> apiList = apiProvider.getAllAPIs();
                StringBuilder apis = new StringBuilder(" and api.context in (");
                if (apiList.size() > 0) {
                    apis.append("'").append(apiList.get(0).getContext()).append("'");
                }
                for (int i = 1; i < apiList.size(); i++) {
                    apis.append(", '").append(apiList.get(i).getContext()).append("' ");
                }
                apis.append(") ");
                where += apis.toString();
            }

            query = query + where + group + order;
            statement = connection.prepareStatement(query);
            //execute
            rs = statement.executeQuery();
            List<ApisByTimeDTO> list = new ArrayList<ApisByTimeDTO>();
            long x, y = 0;
            //iterate over the results
            while (rs.next()) {
                x = rs.getTimestamp("x").getTime();
                y += rs.getLong("y");
                list.add(new ApisByTimeDTO(x, y));
            }
            return list;
        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {

                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {

                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {

                }
            }
        }
    }

    /**
     * List set of subscribers in the current logged user tenant domain
     *
     * @param provider logged user
     * @param limit    result limit
     * @return list of subscribers
     * @throws APIMgtUsageQueryServiceClientException throws if db exception occur
     */
    public static List<DeveloperListDTO> getDeveloperList(String provider, int limit)
            throws APIMgtUsageQueryServiceClientException {

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            //get the connection
            connection = APIMgtDBUtil.getConnection();
            int tenantId = APIUtil.getTenantId(provider);
            String query = "select subc.user_id as id, subc.email_address as email, subc.created_time as time from "
                    + "AM_SUBSCRIBER subc where TENANT_ID=" + tenantId;
            statement = connection.prepareStatement(query);
            //execute
            rs = statement.executeQuery();
            List<DeveloperListDTO> list = new ArrayList<DeveloperListDTO>();
            //iterate over the results
            while (rs.next()) {
                String id = rs.getString("id");
                String email = rs.getString("email");
                String time = rs.getString("time");
                list.add(new DeveloperListDTO(id, email, time));
            }
            return list;

        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException ignore) {

                }
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {

                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {

                }
            }

        }
    }

    /**
     * Get the list of published api
     *
     * @param provider  provider id
     * @param apiFilter Stat type
     * @param limit     limit of the result
     * @return list of apis
     * @throws APIMgtUsageQueryServiceClientException throws if error occurred
     */
    public static List<APIListDTO> getApisList(String provider, String apiFilter, int limit)
            throws APIMgtUsageQueryServiceClientException {
        try {
            APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(provider);
            List<API> apiList;
            if ("allAPIs".equals(apiFilter)) {
                apiList = apiProvider.getAllAPIs();
            } else {
                apiList = apiProvider.getAPIsByProvider(provider);
            }

            List<APIListDTO> list = new ArrayList<APIListDTO>();
            for (API apiInfo : apiList) {
                int count = -1;
                String apiName = apiInfo.getId().getApiName();
                String version = apiInfo.getId().getVersion();
                String apiPublisher = apiInfo.getId().getProviderName();
                list.add(new APIListDTO(count, apiName, version, apiPublisher));
            }
            return list;
        } catch (APIManagementException e) {
            throw new APIMgtUsageQueryServiceClientException("Error occurred while querying from JDBC database", e);
        }
    }
}
