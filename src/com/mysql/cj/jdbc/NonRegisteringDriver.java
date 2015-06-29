/*
  Copyright (c) 2002, 2015, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPLv2
  <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most MySQL Connectors.
  There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
  this software, see the FLOSS License Exception
  <http://www.mysql.com/about/legal/licensing/foss-exception.html>.

  This program is free software; you can redistribute it and/or modify it under the terms
  of the GNU General Public License as published by the Free Software Foundation; version 2
  of the License.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with this
  program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
  Floor, Boston, MA 02110-1301  USA

 */

package com.mysql.cj.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.net.URLDecoder;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.mysql.cj.api.MysqlConnection;
import com.mysql.cj.api.conf.ConnectionPropertiesTransform;
import com.mysql.cj.api.jdbc.JdbcConnection;
import com.mysql.cj.core.Constants;
import com.mysql.cj.core.Messages;
import com.mysql.cj.core.conf.PropertyDefinitions;
import com.mysql.cj.core.exceptions.CJException;
import com.mysql.cj.core.exceptions.ExceptionFactory;
import com.mysql.cj.core.exceptions.InvalidConnectionAttributeException;
import com.mysql.cj.core.exceptions.UnableToConnectException;
import com.mysql.cj.core.io.NetworkResources;
import com.mysql.cj.core.util.StringUtils;
import com.mysql.cj.core.util.Util;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.ha.FailoverConnectionProxy;
import com.mysql.cj.jdbc.ha.LoadBalancingConnectionProxy;
import com.mysql.cj.jdbc.ha.ReplicationConnection;

/**
 * The Java SQL framework allows for multiple database drivers. Each driver should supply a class that implements the Driver interface
 * 
 * <p>
 * The DriverManager will try to load as many drivers as it can find and then for any given connection request, it will ask each driver in turn to try to
 * connect to the target URL.
 * </p>
 * 
 * <p>
 * It is strongly recommended that each Driver class should be small and standalone so that the Driver class can be loaded and queried without bringing in vast
 * quantities of supporting code.
 * </p>
 * 
 * <p>
 * When a Driver class is loaded, it should create an instance of itself and register it with the DriverManager. This means that a user can load and register a
 * driver by doing Class.forName("foo.bah.Driver")
 * </p>
 */
public class NonRegisteringDriver implements java.sql.Driver {
    private static final String ALLOWED_QUOTES = "\"'";

    private static final String REPLICATION_URL_PREFIX = "jdbc:mysql:replication://";

    private static final String URL_PREFIX = "jdbc:mysql://";

    private static final String MXJ_URL_PREFIX = "jdbc:mysql:mxj://";

    public static final String LOADBALANCE_URL_PREFIX = "jdbc:mysql:loadbalance://";

    protected static final ConcurrentHashMap<ConnectionPhantomReference, ConnectionPhantomReference> connectionPhantomRefs = new ConcurrentHashMap<ConnectionPhantomReference, ConnectionPhantomReference>();

    protected static final ReferenceQueue<ConnectionImpl> refQueue = new ReferenceQueue<ConnectionImpl>();

    /*
     * Standardizes OS name information to align with other drivers/clients
     * for MySQL connection attributes
     * 
     * @return the transformed, standardized OS name
     */
    public static String getOSName() {
        return Constants.OS_NAME;
    }

    /*
     * Standardizes platform information to align with other drivers/clients
     * for MySQL connection attributes
     * 
     * @return the transformed, standardized platform details
     */
    public static String getPlatform() {
        return Constants.OS_ARCH;
    }

    static {
        AbandonedConnectionCleanupThread referenceThread = new AbandonedConnectionCleanupThread();
        referenceThread.setDaemon(true);
        referenceThread.start();
    }

    /**
     * Gets the drivers major version number
     * 
     * @return the drivers major version number
     */
    static int getMajorVersionInternal() {
        return safeIntParse(Constants.CJ_MAJOR_VERSION);
    }

    /**
     * Get the drivers minor version number
     * 
     * @return the drivers minor version number
     */
    static int getMinorVersionInternal() {
        return safeIntParse(Constants.CJ_MINOR_VERSION);
    }

    /**
     * Parses hostPortPair in the form of [host][:port] into an array, with the
     * element of index HOST_NAME_INDEX being the host (or null if not
     * specified), and the element of index PORT_NUMBER_INDEX being the port (or
     * null if not specified).
     * 
     * @param hostPortPair
     *            host and port in form of of [host][:port]
     * 
     * @return array containing host and port as Strings
     * 
     */
    public static String[] parseHostPortPair(String hostPortPair) {

        String[] splitValues = new String[2];

        if (StringUtils.startsWithIgnoreCaseAndWs(hostPortPair, "address")) {
            splitValues[PropertyDefinitions.HOST_NAME_INDEX] = hostPortPair.trim();
            splitValues[PropertyDefinitions.PORT_NUMBER_INDEX] = null;

            return splitValues;
        }

        int portIndex = hostPortPair.indexOf(":");

        String hostname = null;

        if (portIndex != -1) {
            if ((portIndex + 1) < hostPortPair.length()) {
                String portAsString = hostPortPair.substring(portIndex + 1);
                hostname = hostPortPair.substring(0, portIndex);

                splitValues[PropertyDefinitions.HOST_NAME_INDEX] = hostname;

                splitValues[PropertyDefinitions.PORT_NUMBER_INDEX] = portAsString;
            } else {
                throw ExceptionFactory.createException(InvalidConnectionAttributeException.class, Messages.getString("NonRegisteringDriver.37"));
            }
        } else {
            splitValues[PropertyDefinitions.HOST_NAME_INDEX] = hostPortPair;
            splitValues[PropertyDefinitions.PORT_NUMBER_INDEX] = null;
        }

        return splitValues;
    }

    private static int safeIntParse(String intAsString) {
        try {
            return Integer.parseInt(intAsString);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /**
     * Construct a new driver and register it with DriverManager
     * 
     * @throws SQLException
     *             if a database error occurs.
     */
    public NonRegisteringDriver() throws SQLException {
        // Required for Class.forName().newInstance()
    }

    /**
     * Typically, drivers will return true if they understand the subprotocol
     * specified in the URL and false if they don't. This driver's protocols
     * start with jdbc:mysql:
     * 
     * @param url
     *            the URL of the driver
     * 
     * @return true if this driver accepts the given URL
     * 
     * @exception SQLException
     *                if a database-access error occurs
     * 
     * @see java.sql.Driver#acceptsURL
     */
    public boolean acceptsURL(String url) throws SQLException {
        return (parseURL(url, null) != null);
    }

    //
    // return the database name property
    //

    /**
     * Try to make a database connection to the given URL. The driver should
     * return "null" if it realizes it is the wrong kind of driver to connect to
     * the given URL. This will be common, as when the JDBC driverManager is
     * asked to connect to a given URL, it passes the URL to each loaded driver
     * in turn.
     * 
     * <p>
     * The driver should raise an SQLException if it is the right driver to connect to the given URL, but has trouble connecting to the database.
     * </p>
     * 
     * <p>
     * The java.util.Properties argument can be used to pass arbitrary string tag/value pairs as connection arguments.
     * </p>
     * 
     * <p>
     * My protocol takes the form:
     * 
     * <PRE>
     * 
     * jdbc:mysql://host:port/database
     * 
     * </PRE>
     * 
     * </p>
     * 
     * @param url
     *            the URL of the database to connect to
     * @param info
     *            a list of arbitrary tag/value pairs as connection arguments
     * 
     * @return a connection to the URL or null if it isnt us
     * 
     * @exception SQLException
     *                if a database access error occurs
     * 
     * @see java.sql.Driver#connect
     */
    public java.sql.Connection connect(String url, Properties info) throws SQLException {
        if (url != null) {
            if (StringUtils.startsWithIgnoreCase(url, LOADBALANCE_URL_PREFIX)) {
                return connectLoadBalanced(url, info);
            } else if (StringUtils.startsWithIgnoreCase(url, REPLICATION_URL_PREFIX)) {
                return connectReplicationConnection(url, info);
            }
        }

        Properties props = null;

        if ((props = parseURL(url, info)) == null) {
            return null;
        }

        if (!"1".equals(props.getProperty(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY))) {
            return connectFailover(url, info);
        }

        try {
            JdbcConnection newConn = com.mysql.cj.jdbc.ConnectionImpl.getInstance(host(props), port(props), props, database(props), url);

            return newConn;
        } catch (CJException ex) {
            throw ExceptionFactory.createException(UnableToConnectException.class,
                    Messages.getString("NonRegisteringDriver.17", new Object[] { ex.toString() }), ex);
        }
    }

    protected static void trackConnection(JdbcConnection newConn) {

        ConnectionPhantomReference phantomRef = new ConnectionPhantomReference((ConnectionImpl) newConn, refQueue);
        connectionPhantomRefs.put(phantomRef, phantomRef);
    }

    private java.sql.Connection connectLoadBalanced(String url, Properties info) throws SQLException {
        Properties parsedProps = parseURL(url, info);

        if (parsedProps == null) {
            return null;
        }

        // People tend to drop this in, it doesn't make sense
        parsedProps.remove(PropertyDefinitions.PNAME_roundRobinLoadBalance);

        int numHosts = Integer.parseInt(parsedProps.getProperty(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY));

        List<String> hostList = new ArrayList<String>();

        for (int i = 0; i < numHosts; i++) {
            int index = i + 1;

            hostList.add(parsedProps.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY + "." + index) + ":"
                    + parsedProps.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY + "." + index));
        }

        LoadBalancingConnectionProxy proxyBal = new LoadBalancingConnectionProxy(hostList, parsedProps);

        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(this.getClass().getClassLoader(),
                new Class[] { com.mysql.cj.api.jdbc.ha.LoadBalancedConnection.class }, proxyBal);
    }

    private java.sql.Connection connectFailover(String url, Properties info) throws SQLException {
        Properties parsedProps = parseURL(url, info);

        if (parsedProps == null) {
            return null;
        }

        // People tend to drop this in, it doesn't make sense
        parsedProps.remove(PropertyDefinitions.PNAME_roundRobinLoadBalance);

        int numHosts = Integer.parseInt(parsedProps.getProperty(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY));

        List<String> hostList = new ArrayList<String>();

        for (int i = 0; i < numHosts; i++) {
            int index = i + 1;

            hostList.add(parsedProps.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY + "." + index) + ":"
                    + parsedProps.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY + "." + index));
        }

        FailoverConnectionProxy connProxy = new FailoverConnectionProxy(hostList, parsedProps);

        return (java.sql.Connection) java.lang.reflect.Proxy
                .newProxyInstance(this.getClass().getClassLoader(), new Class[] { JdbcConnection.class }, connProxy);
    }

    public java.sql.Connection connectReplicationConnection(String url, Properties info) throws SQLException {
        Properties parsedProps = parseURL(url, info);

        if (parsedProps == null) {
            return null;
        }

        Properties masterProps = (Properties) parsedProps.clone();
        Properties slavesProps = (Properties) parsedProps.clone();

        // Marker used for further testing later on, also when
        // debugging
        slavesProps.setProperty("com.mysql.jdbc.ReplicationConnection.isSlave", "true");

        int numHosts = Integer.parseInt(parsedProps.getProperty(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY));

        if (numHosts < 2) {
            throw SQLError.createSQLException(Messages.getString("NonRegisteringDriver.41"), SQLError.SQL_STATE_INVALID_CONNECTION_ATTRIBUTE, null);
        }
        List<String> slaveHostList = new ArrayList<String>();
        List<String> masterHostList = new ArrayList<String>();

        String firstHost = masterProps.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY + ".1") + ":"
                + masterProps.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY + ".1");

        boolean usesExplicitServerType = NonRegisteringDriver.isHostPropertiesList(firstHost);

        for (int i = 0; i < numHosts; i++) {
            int index = i + 1;

            masterProps.remove(PropertyDefinitions.HOST_PROPERTY_KEY + "." + index);
            masterProps.remove(PropertyDefinitions.PORT_PROPERTY_KEY + "." + index);
            slavesProps.remove(PropertyDefinitions.HOST_PROPERTY_KEY + "." + index);
            slavesProps.remove(PropertyDefinitions.PORT_PROPERTY_KEY + "." + index);

            String host = parsedProps.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY + "." + index);
            String port = parsedProps.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY + "." + index);
            if (usesExplicitServerType) {
                if (isHostMaster(host)) {
                    masterHostList.add(host);
                } else {
                    slaveHostList.add(host);
                }
            } else {
                if (i == 0) {
                    masterHostList.add(host + ":" + port);
                } else {
                    slaveHostList.add(host + ":" + port);
                }
            }
        }

        slavesProps.remove(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY);
        masterProps.remove(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY);
        masterProps.remove(PropertyDefinitions.HOST_PROPERTY_KEY);
        masterProps.remove(PropertyDefinitions.PORT_PROPERTY_KEY);
        slavesProps.remove(PropertyDefinitions.HOST_PROPERTY_KEY);
        slavesProps.remove(PropertyDefinitions.PORT_PROPERTY_KEY);

        return new ReplicationConnection(masterProps, slavesProps, masterHostList, slaveHostList);
    }

    private boolean isHostMaster(String host) {
        if (NonRegisteringDriver.isHostPropertiesList(host)) {
            Properties hostSpecificProps = NonRegisteringDriver.expandHostKeyValues(host);
            if (hostSpecificProps.containsKey("type") && "master".equalsIgnoreCase(hostSpecificProps.get("type").toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the database property from <code>props</code>
     * 
     * @param props
     *            the Properties to look for the database property.
     * 
     * @return the database name.
     */
    public String database(Properties props) {
        return props.getProperty(PropertyDefinitions.DBNAME_PROPERTY_KEY);
    }

    /**
     * Gets the drivers major version number
     * 
     * @return the drivers major version number
     */
    public int getMajorVersion() {
        return getMajorVersionInternal();
    }

    /**
     * Get the drivers minor version number
     * 
     * @return the drivers minor version number
     */
    public int getMinorVersion() {
        return getMinorVersionInternal();
    }

    /**
     * The getPropertyInfo method is intended to allow a generic GUI tool to
     * discover what properties it should prompt a human for in order to get
     * enough information to connect to a database.
     * 
     * <p>
     * Note that depending on the values the human has supplied so far, additional values may become necessary, so it may be necessary to iterate through
     * several calls to getPropertyInfo
     * </p>
     * 
     * @param url
     *            the Url of the database to connect to
     * @param info
     *            a proposed list of tag/value pairs that will be sent on
     *            connect open.
     * 
     * @return An array of DriverPropertyInfo objects describing possible
     *         properties. This array may be an empty array if no properties are
     *         required
     * 
     * @exception SQLException
     *                if a database-access error occurs
     * 
     * @see java.sql.Driver#getPropertyInfo
     */
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        if (info == null) {
            info = new Properties();
        }

        if ((url != null) && url.startsWith(URL_PREFIX)) {
            info = parseURL(url, info);
        }

        DriverPropertyInfo hostProp = new DriverPropertyInfo(PropertyDefinitions.HOST_PROPERTY_KEY, info.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY));
        hostProp.required = true;
        hostProp.description = Messages.getString("NonRegisteringDriver.3");

        DriverPropertyInfo portProp = new DriverPropertyInfo(PropertyDefinitions.PORT_PROPERTY_KEY, info.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY,
                "3306"));
        portProp.required = false;
        portProp.description = Messages.getString("NonRegisteringDriver.7");

        DriverPropertyInfo dbProp = new DriverPropertyInfo(PropertyDefinitions.DBNAME_PROPERTY_KEY, info.getProperty(PropertyDefinitions.DBNAME_PROPERTY_KEY));
        dbProp.required = false;
        dbProp.description = "Database name";

        DriverPropertyInfo userProp = new DriverPropertyInfo(PropertyDefinitions.PNAME_user, info.getProperty(PropertyDefinitions.PNAME_user));
        userProp.required = true;
        userProp.description = Messages.getString("NonRegisteringDriver.13");

        DriverPropertyInfo passwordProp = new DriverPropertyInfo(PropertyDefinitions.PNAME_password, info.getProperty(PropertyDefinitions.PNAME_password));
        passwordProp.required = true;
        passwordProp.description = Messages.getString("NonRegisteringDriver.16");

        DriverPropertyInfo[] dpi;
        dpi = new JdbcPropertySetImpl().exposeAsDriverPropertyInfo(info, 5);

        dpi[0] = hostProp;
        dpi[1] = portProp;
        dpi[2] = dbProp;
        dpi[3] = userProp;
        dpi[4] = passwordProp;

        return dpi;
    }

    //
    // return the value of any property this driver knows about
    //

    /**
     * Returns the hostname property
     * 
     * @param props
     *            the java.util.Properties instance to retrieve the hostname
     *            from.
     * 
     * @return the hostname
     */
    public String host(Properties props) {
        return props.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY, "localhost");
    }

    /**
     * Report whether the driver is a genuine JDBC compliant driver. A driver
     * may only report "true" here if it passes the JDBC compliance tests,
     * otherwise it is required to return false. JDBC compliance requires full
     * support for the JDBC API and full support for SQL 92 Entry Level.
     * 
     * <p>
     * MySQL is not SQL92 compliant
     * </p>
     * 
     * @return is this driver JDBC compliant?
     */
    public boolean jdbcCompliant() {
        return false;
    }

    public Properties parseURL(String url, Properties defaults) {
        Properties urlProps = (defaults != null) ? new Properties(defaults) : new Properties();

        if (url == null) {
            return null;
        }

        if (!StringUtils.startsWithIgnoreCase(url, URL_PREFIX) && !StringUtils.startsWithIgnoreCase(url, MXJ_URL_PREFIX)
                && !StringUtils.startsWithIgnoreCase(url, LOADBALANCE_URL_PREFIX) && !StringUtils.startsWithIgnoreCase(url, REPLICATION_URL_PREFIX)) {

            return null;
        }

        int beginningOfSlashes = url.indexOf("//");

        if (StringUtils.startsWithIgnoreCase(url, MXJ_URL_PREFIX)) {

            urlProps.setProperty(PropertyDefinitions.PNAME_socketFactory, "com.mysql.management.driverlaunched.ServerLauncherSocketFactory");
        }

        /*
         * Parse parameters after the ? in the URL and remove them from the
         * original URL.
         */
        int index = url.indexOf("?");

        if (index != -1) {
            String paramString = url.substring(index + 1, url.length());
            url = url.substring(0, index);

            StringTokenizer queryParams = new StringTokenizer(paramString, "&");

            while (queryParams.hasMoreTokens()) {
                String parameterValuePair = queryParams.nextToken();

                int indexOfEquals = StringUtils.indexOfIgnoreCase(0, parameterValuePair, "=");

                String parameter = null;
                String value = null;

                if (indexOfEquals != -1) {
                    parameter = parameterValuePair.substring(0, indexOfEquals);

                    if (indexOfEquals + 1 < parameterValuePair.length()) {
                        value = parameterValuePair.substring(indexOfEquals + 1);
                    }
                }

                if ((value != null && value.length() > 0) && (parameter != null && parameter.length() > 0)) {
                    try {
                        urlProps.put(parameter, URLDecoder.decode(value, "UTF-8"));
                    } catch (UnsupportedEncodingException badEncoding) {
                        // punt
                        urlProps.put(parameter, URLDecoder.decode(value));
                    } catch (NoSuchMethodError nsme) {
                        // punt again
                        urlProps.put(parameter, URLDecoder.decode(value));
                    }
                }
            }
        }

        url = url.substring(beginningOfSlashes + 2);

        String hostStuff = null;

        int slashIndex = StringUtils.indexOfIgnoreCase(0, url, "/", ALLOWED_QUOTES, ALLOWED_QUOTES, StringUtils.SEARCH_MODE__ALL);

        if (slashIndex != -1) {
            hostStuff = url.substring(0, slashIndex);

            if ((slashIndex + 1) < url.length()) {
                urlProps.put(PropertyDefinitions.DBNAME_PROPERTY_KEY, url.substring((slashIndex + 1), url.length()));
            }
        } else {
            hostStuff = url;
        }

        int numHosts = 0;

        if ((hostStuff != null) && (hostStuff.trim().length() > 0)) {
            List<String> hosts = StringUtils.split(hostStuff, ",", ALLOWED_QUOTES, ALLOWED_QUOTES, false);

            for (String hostAndPort : hosts) {
                numHosts++;

                String[] hostPortPair = parseHostPortPair(hostAndPort);

                if (hostPortPair[PropertyDefinitions.HOST_NAME_INDEX] != null && hostPortPair[PropertyDefinitions.HOST_NAME_INDEX].trim().length() > 0) {
                    urlProps.setProperty(PropertyDefinitions.HOST_PROPERTY_KEY + "." + numHosts, hostPortPair[PropertyDefinitions.HOST_NAME_INDEX]);
                } else {
                    urlProps.setProperty(PropertyDefinitions.HOST_PROPERTY_KEY + "." + numHosts, "localhost");
                }

                if (hostPortPair[PropertyDefinitions.PORT_NUMBER_INDEX] != null) {
                    urlProps.setProperty(PropertyDefinitions.PORT_PROPERTY_KEY + "." + numHosts, hostPortPair[PropertyDefinitions.PORT_NUMBER_INDEX]);
                } else {
                    urlProps.setProperty(PropertyDefinitions.PORT_PROPERTY_KEY + "." + numHosts, "3306");
                }
            }
        } else {
            numHosts = 1;
            urlProps.setProperty(PropertyDefinitions.HOST_PROPERTY_KEY + ".1", "localhost");
            urlProps.setProperty(PropertyDefinitions.PORT_PROPERTY_KEY + ".1", "3306");
        }

        urlProps.setProperty(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY, String.valueOf(numHosts));
        urlProps.setProperty(PropertyDefinitions.HOST_PROPERTY_KEY, urlProps.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY + ".1"));
        urlProps.setProperty(PropertyDefinitions.PORT_PROPERTY_KEY, urlProps.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY + ".1"));

        String propertiesTransformClassName = urlProps.getProperty(PropertyDefinitions.PNAME_propertiesTransform);

        if (propertiesTransformClassName != null) {
            try {
                ConnectionPropertiesTransform propTransformer = (ConnectionPropertiesTransform) Class.forName(propertiesTransformClassName).newInstance();

                urlProps = propTransformer.transformProperties(urlProps);
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | CJException e) {
                throw ExceptionFactory.createException(InvalidConnectionAttributeException.class,
                        Messages.getString("NonRegisteringDriver.38", new Object[] { propertiesTransformClassName, e.toString() }), e);
            }
        }

        if (Util.isColdFusion() && urlProps.getProperty(PropertyDefinitions.PNAME_autoConfigureForColdFusion, "true").equalsIgnoreCase("true")) {
            String configs = urlProps.getProperty(PropertyDefinitions.PNAME_useConfigs);

            StringBuilder newConfigs = new StringBuilder();

            if (configs != null) {
                newConfigs.append(configs);
                newConfigs.append(",");
            }

            newConfigs.append("coldFusion");

            urlProps.setProperty(PropertyDefinitions.PNAME_useConfigs, newConfigs.toString());
        }

        // If we use a config, it actually should get overridden by anything in the URL or passed-in properties

        String configNames = null;

        if (defaults != null) {
            configNames = defaults.getProperty(PropertyDefinitions.PNAME_useConfigs);
        }

        if (configNames == null) {
            configNames = urlProps.getProperty(PropertyDefinitions.PNAME_useConfigs);
        }

        if (configNames != null) {
            List<String> splitNames = StringUtils.split(configNames, ",", true);

            Properties configProps = new Properties();

            Iterator<String> namesIter = splitNames.iterator();

            while (namesIter.hasNext()) {
                String configName = namesIter.next();

                try {
                    InputStream configAsStream = MysqlConnection.class.getResourceAsStream("../configurations/" + configName + ".properties");

                    if (configAsStream == null) {
                        throw ExceptionFactory.createException(InvalidConnectionAttributeException.class,
                                Messages.getString("NonRegisteringDriver.39", new Object[] { configName }));
                    }
                    configProps.load(configAsStream);
                } catch (IOException ioEx) {
                    throw ExceptionFactory.createException(InvalidConnectionAttributeException.class,
                            Messages.getString("NonRegisteringDriver.40", new Object[] { configName }));
                }
            }

            Iterator<Object> propsIter = urlProps.keySet().iterator();

            while (propsIter.hasNext()) {
                String key = propsIter.next().toString();
                String property = urlProps.getProperty(key);
                configProps.setProperty(key, property);
            }

            urlProps = configProps;
        }

        // Properties passed in should override ones in URL

        if (defaults != null) {
            Iterator<Object> propsIter = defaults.keySet().iterator();

            while (propsIter.hasNext()) {
                String key = propsIter.next().toString();
                if (!key.equals(PropertyDefinitions.NUM_HOSTS_PROPERTY_KEY)) {
                    String property = defaults.getProperty(key);
                    urlProps.setProperty(key, property);
                }
            }
        }

        return urlProps;
    }

    /**
     * Returns the port number property
     * 
     * @param props
     *            the properties to get the port number from
     * 
     * @return the port number
     */
    public int port(Properties props) {
        return Integer.parseInt(props.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY, "3306"));
    }

    /**
     * Returns the given property from <code>props</code>
     * 
     * @param name
     *            the property name
     * @param props
     *            the property instance to look in
     * 
     * @return the property value, or null if not found.
     */
    public String property(String name, Properties props) {
        return props.getProperty(name);
    }

    /**
     * Expands hosts of the form address=(protocol=tcp)(host=localhost)(port=3306)
     * into a java.util.Properties. Special characters (in this case () and =) must be quoted.
     * Any values that are string-quoted ("" or '') are also stripped of quotes.
     */
    public static Properties expandHostKeyValues(String host) {
        Properties hostProps = new Properties();

        if (isHostPropertiesList(host)) {
            host = host.substring("address=".length() + 1);
            List<String> hostPropsList = StringUtils.split(host, ")", "'\"", "'\"", true);

            for (String propDef : hostPropsList) {
                if (propDef.startsWith("(")) {
                    propDef = propDef.substring(1);
                }

                List<String> kvp = StringUtils.split(propDef, "=", "'\"", "'\"", true);

                String key = kvp.get(0);
                String value = kvp.size() > 1 ? kvp.get(1) : null;

                if (value != null && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }

                if (value != null) {
                    if (PropertyDefinitions.HOST_PROPERTY_KEY.equalsIgnoreCase(key) || PropertyDefinitions.DBNAME_PROPERTY_KEY.equalsIgnoreCase(key)
                            || PropertyDefinitions.PORT_PROPERTY_KEY.equalsIgnoreCase(key) || PropertyDefinitions.PROTOCOL_PROPERTY_KEY.equalsIgnoreCase(key)
                            || PropertyDefinitions.PATH_PROPERTY_KEY.equalsIgnoreCase(key)) {
                        key = key.toUpperCase(Locale.ENGLISH);
                    } else if (PropertyDefinitions.PNAME_user.equalsIgnoreCase(key) || PropertyDefinitions.PNAME_password.equalsIgnoreCase(key)) {
                        key = key.toLowerCase(Locale.ENGLISH);
                    }

                    hostProps.setProperty(key, value);
                }
            }
        }

        return hostProps;
    }

    public static boolean isHostPropertiesList(String host) {
        return host != null && StringUtils.startsWithIgnoreCase(host, "address=");
    }

    static class ConnectionPhantomReference extends PhantomReference<ConnectionImpl> {
        private NetworkResources io;

        ConnectionPhantomReference(ConnectionImpl connectionImpl, ReferenceQueue<ConnectionImpl> q) {
            super(connectionImpl, q);

            this.io = connectionImpl.getProtocol().getSocketConnection().getNetworkResources();
        }

        void cleanup() {
            if (this.io != null) {
                try {
                    this.io.forceClose();
                } finally {
                    this.io = null;
                }
            }
        }
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
