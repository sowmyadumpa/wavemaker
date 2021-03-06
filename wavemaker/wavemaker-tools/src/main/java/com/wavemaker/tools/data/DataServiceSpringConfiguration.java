/*
 *  Copyright (C) 2008-2013 VMware, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.tools.data;

import static org.springframework.util.StringUtils.hasText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jndi.JndiObjectFactoryBean;

import com.wavemaker.common.util.ObjectUtils;
import com.wavemaker.common.util.StringUtils;
import com.wavemaker.common.util.SystemUtils;
import com.wavemaker.runtime.data.spring.ConfigurationAndSessionFactoryBean;
import com.wavemaker.runtime.data.spring.SpringDataServiceManager;
import com.wavemaker.runtime.data.spring.WMPropertyPlaceholderConfigurer;
import com.wavemaker.runtime.data.sqlserver.SqlServerUserImpersonatingDataSourceProxy;
import com.wavemaker.runtime.data.util.DataServiceConstants;
import com.wavemaker.tools.common.ConfigurationException;
import com.wavemaker.tools.data.util.DataServiceUtils;
import com.wavemaker.tools.io.File;
import com.wavemaker.tools.service.FileService;
import com.wavemaker.tools.spring.beans.Alias;
import com.wavemaker.tools.spring.beans.Bean;
import com.wavemaker.tools.spring.beans.Beans;
import com.wavemaker.tools.spring.beans.ConstructorArg;
import com.wavemaker.tools.spring.beans.DefaultableBoolean;
import com.wavemaker.tools.spring.beans.Entry;
import com.wavemaker.tools.spring.beans.Map;
import com.wavemaker.tools.spring.beans.Prop;
import com.wavemaker.tools.spring.beans.Property;
import com.wavemaker.tools.spring.beans.Props;
import com.wavemaker.tools.spring.beans.Value;
import com.wavemaker.tools.deployment.DeploymentType;

/**
 * Encapsulates access to the Data Model Spring configuration.
 * 
 * @author Simon Toens
 * @author Jeremy Grelle
 */
public class DataServiceSpringConfiguration {

    public static final String JNDI_NAME_PROPERTY = "jndiName";

    private static final String HIBERNATE_DIALECT = "hibernate.dialect";
    private static final String MSSQL_INNODB = "org.hibernate.dialect.MySQLInnoDBDialect";
    public static final String MYSQL_DIALECT = "com.wavemaker.runtime.data.dialect.MySQLDialect";

    private final String rootPath;

    private final String path;

    private final FileService fileService;

    private final String propertiesFile;

    private final String serviceId;

    private Beans beans;

    private boolean isDirty = false;

    public DataServiceSpringConfiguration(FileService fileService, String rootPath, File configFile, String serviceName) {
        this(fileService, rootPath, configFile.getName(), serviceName);
    }

    public DataServiceSpringConfiguration(FileService fileService, String rootPath, String configFileName, String serviceName) {
        this.rootPath = rootPath;

        this.path = StringUtils.appendPaths(this.rootPath, configFileName);

        this.fileService = fileService;
        this.beans = DataServiceUtils.readBeans(fileService, this.path);
        this.serviceId = serviceName;
        this.propertiesFile = getConnectionPropertiesFileName();
    }

    void revert() {
        this.beans = DataServiceUtils.readBeans(this.fileService, this.path);
        this.isDirty = false;
    }

    void write() {

        if (!this.isDirty) {
            if (DataServiceLoggers.parserLogger.isDebugEnabled()) {
                DataServiceLoggers.parserLogger.info("No changes to write to Spring Configuration at " + this.path);
            }
            return;

        }

        DataServiceUtils.writeBeans(this.beans, this.fileService, this.path);

        if (DataServiceLoggers.parserLogger.isInfoEnabled()) {
            DataServiceLoggers.parserLogger.info("Wrote Spring Configuration at " + this.path);
        }

        this.isDirty = false;

    }

    String getPath() {
        return this.path;
    }

    Properties readProperties() {
        return readProperties(false);
    }

    Properties readProperties(boolean removePrefix) {
        try {
            String s = this.fileService.readFile(this.propertiesFile);
            ByteArrayInputStream bais = new ByteArrayInputStream(s.getBytes());
            Properties rtn = DataServiceUtils.readProperties(bais);
            if (removePrefix) {
                DataServiceUtils.removePrefix(rtn);
            }
            return rtn;
        } catch (IOException ex) {
            throw new ConfigurationException(ex);
        }
    }

    Properties readCombinedProperties(boolean removePrefix) {
        Properties props = readProperties(removePrefix);
        readExecuteAsProperties(props);
        return props;
    }

    void readExecuteAsProperties(Properties props) {
        List<Bean> dsProxyBeans = this.beans.getBeansByType(SqlServerUserImpersonatingDataSourceProxy.class);
        if (dsProxyBeans.size() == 0) {
            return;
        }
        props.put("executeAs", "true");
        Bean proxyBean = dsProxyBeans.get(0);
        props.put("activeDirectoryDomain", proxyBean.getProperty("activeDirectoryDomain").getValue());
    }

    boolean useIndividualCRUDperations() {
        String s = getPropertyValue(DataServiceConstants.GENERATE_OLD_STYLE_OPRS_PROPERTY);
        return Boolean.valueOf(s);
    }

    void setRefreshEntities(List<String> refreshEntities) {
        setPropertyValue(DataServiceConstants.REFRESH_ENTITIES_PROPERTY, ObjectUtils.toString(refreshEntities));
    }

    List<String> getRefreshEntities() {
        String s = getPropertyValue(DataServiceConstants.REFRESH_ENTITIES_PROPERTY);
        if (s == null) {
            return new ArrayList<String>();
        } else {
            return StringUtils.split(s);
        }
    }

    public void writeProperties(Properties props) {
        writeProperties(props, true);
    }

    public void writeProperties(Properties props, boolean validate) {

        if (!validate) {
            updateSpringConfigIfNecessary(props);
            writeProps(filterProps(props));
            return;
        }

        // when sent from the client, there's no prefix. however, when called as
        // an api, as some of the tests do, there may be a prefix
        DataServiceUtils.removePrefix(props);

        String key = StringUtils.removeIfStartsWith(DataServiceConstants.DB_URL, ".");
        String connectionUrl = props.getProperty(key);
        if (connectionUrl == null) {
            throw new IllegalArgumentException(key + " must be set");
        }

        key = StringUtils.removeIfStartsWith(DataServiceConstants.DB_DRIVER_CLASS_NAME, ".");
        String driverClassName = props.getProperty(key);
        if (ObjectUtils.isNullOrEmpty(driverClassName)) {
            String dbtype = BaseDataModelSetup.getDBTypeFromURL(connectionUrl);
            if (dbtype == null) {
                throw new IllegalArgumentException(key + " must bet set");
            }
            String value = BaseDataModelSetup.getDriverClassForDBType(dbtype);
            props.setProperty(key, value);
        }

        key = StringUtils.removeIfStartsWith(DataServiceConstants.DB_DIALECT, ".");
        String dialect = props.getProperty(key);
        if (ObjectUtils.isNullOrEmpty(dialect)) {
            String dbtype = BaseDataModelSetup.getDBTypeFromURL(connectionUrl);
            if (dbtype == null) {
                throw new IllegalArgumentException(key + " must bet set");
            }
            String value = BaseDataModelSetup.getDialectForDBType(dbtype);
            props.setProperty(key, value);
        }

        Properties org = readCombinedProperties(false);

        DataServiceUtils.removePrefix(org);

        // don't override properties that can't be edited in the UI
        SystemUtils.addAllUnlessSet(props, org);

        // don't do anything unless some changes have been made
        if (props.equals(org)) {
            return;
        }

        updateSpringConfigIfNecessary(props);
        writeProps(DataServiceUtils.addPrefix(this.serviceId, filterProps(props)));
    }

    public String getServiceId() {
        return this.serviceId;
    }

    private Properties filterProps(Properties props) {
        Properties filtered = new Properties();
        filtered.putAll(props);
        filtered.remove("executeAs");
        filtered.remove(this.serviceId + ".executeAs");
        filtered.remove("activeDirectoryDomain");
        filtered.remove(this.serviceId + ".activeDirectoryDomain");
        return filtered;
    }

    void addMapping(String path) {
        Property p = getMappingFilesProperty();
        List<String> l = new ArrayList<String>(p.getListValue());
        l.add(path);
        setMappings(l);
    }

    void removeMapping(String path) {
        Property p = getMappingFilesProperty();
        List<String> l = new ArrayList<String>(p.getListValue());
        l.remove(path);
        setMappings(l);
    }

    List<String> getMappings() {
        return getMappingFilesProperty().getListValue();
    }

    public boolean isKnownConfiguration() {
        try {
            getSpringDataServiceManager();
            return true;
        } catch (SpringDataServiceManagerNotFound ex) {
            return false;
        }
    }

    public List<Bean> getBeansByType(Class<?> type) {
        return this.beans.getBeansByType(type.getName());
    }

    // Add dummy beans used to export table structure when deploying to cloud foundry
    public void createAuxSessionFactoryBeans(DeploymentType type) {
        if (type != DeploymentType.CLOUD_FOUNDRY) {
            return;
        }

        List<Bean> factoryBeans = this.beans.getBeansByType(DataServiceConstants.SESSION_FACTORY_BEAN_CLASS);
        if (factoryBeans != null && factoryBeans.size() > 0) {
            for (Bean bean : factoryBeans) {
                Bean auxBean = new Bean();
                auxBean.setId(bean.getId() + DataServiceConstants.AUX_BEAN_SUFFIX);
                auxBean.setAbstract(bean.getAbstract());
                //auxBean.setAutowire(bean.getAutowire());
                auxBean.setClazz(bean.getClazz() + DataServiceConstants.AUX_BEAN_SUFFIX);
                //auxBean.setDependencyCheck(bean.getDependencyCheck());
                //auxBean.setDependsOn(bean.getDependsOn());
                auxBean.setDescription(bean.getDescription());
                auxBean.setDestroyMethod(bean.getDestroyMethod());
                auxBean.setFactoryBean(bean.getFactoryBean());
                auxBean.setFactoryMethod(bean.getFactoryMethod());
                auxBean.setInitMethod(bean.getInitMethod());
                auxBean.setLazyInit(bean.getLazyInit());
                auxBean.setMetasAndConstructorArgsAndProperties(bean.getMetasAndConstructorArgsAndProperties());
                auxBean.setName(bean.getName());
                auxBean.setParent(bean.getParent());
                auxBean.setScope(bean.getScope());
                this.beans.addBean(auxBean);
            }
            this.isDirty = true;
        }
    }

    void configureJNDIDataSource(String jndiName) {

        List<Bean> l = this.beans.getBeansByType(DriverManagerDataSource.class.getName());

        if (l.size() != 1) {
            throw new AssertionError("Expected one datasource bean");
        }

        Bean ds = l.iterator().next();
        ds.setClazz(JndiObjectFactoryBean.class.getName());
        ds.removeProperties();
        ds.addProperty(JNDI_NAME_PROPERTY, jndiName);
        this.isDirty = true;
    }

    /**
     * @param dbName
     */
    void configureDbAlias(String dbName, DeploymentType type, String dialect) {
        if (!hasText(dbName)) {
            return;
        }

        List<Bean> l = this.beans.getBeansByType(DriverManagerDataSource.class.getName());

        if (l.size() != 1) {
            throw new AssertionError("Expected one datasource bean");
        }

        Bean ds = l.get(0);

        if (type == DeploymentType.CLOUD_FOUNDRY && dialect != null && dialect.equals(this.MYSQL_DIALECT)) {
            String id = ds.getId();
            this.beans.removeBeanById(id);
            this.isDirty = true;
        } else if (!ds.getId().equals(dbName + "DataSource")) {
            Alias dsAlias = new Alias();
            dsAlias.setName(ds.getId());
            dsAlias.setAlias(dbName);
            this.beans.addAlias(dsAlias);
            this.isDirty = true;
        }
    }

    void configureHibernateSchemaUpdate(String serviceId, String updateSchema) {
        if (hasText(updateSchema) && Boolean.parseBoolean(updateSchema)) {

            List<Bean> l = this.beans.getBeansByType(ConfigurationAndSessionFactoryBean.class);

            if (l.size() != 1) {
                throw new AssertionError("Expected one session factory bean");
            }

            Bean sf = l.get(0);
            Property hibernatePropsProperty = sf.getProperty("hibernateProperties");
            if (hibernatePropsProperty != null) {
                Props propValues = hibernatePropsProperty.getProps();
                Prop ddlProp = new Prop();
                ddlProp.setKey("hibernate.hbm2ddl.auto");
                String[] value = { "update" };
                ddlProp.setContent(Arrays.asList(value));
                propValues.getProps().add(ddlProp);
                this.isDirty = true;

                updateDialect(serviceId, propValues);
            }
        }
    }

    private void updateDialect(String serviceId, Props propValues) {
        Properties properties = readProperties(false);
        if (properties.get(serviceId + ".dialect") != null && !properties.get(serviceId + ".dialect").equals(MYSQL_DIALECT)) {
            return;
        }
        
        boolean dialectFound = false;
        for (Prop prop : propValues.getProps()) {
            if (prop.getKey().equals(HIBERNATE_DIALECT)) {
                dialectFound = true;
                List<String> val = new ArrayList<String>();
                val.add(MSSQL_INNODB);
                prop.setContent(val);
                break;
            }
        }
        if (!dialectFound) {
            Prop prop = new Prop();
            prop.setKey(HIBERNATE_DIALECT);
            List<String> val = new ArrayList<String>();
            val.add(MSSQL_INNODB);
            prop.setContent(val);
            propValues.getProps().add(prop);
        }
    }

    private void setPropertyValue(String key, String value) {
        Map m = getOrCreatePropertiesMap();
        Entry e = getEntry(m, key, true);
        e.setValue(value);
        this.isDirty = true;
    }

    private String getPropertyValue(String key) {
        Map m = getOrCreatePropertiesMap();
        Entry e = getEntry(m, key, false);
        if (e == null) {
            return null;
        } else {
            return e.getValue();
        }
    }

    private Entry getEntry(Map m, String key, boolean create) {
        for (Entry e : m.getEntries()) {
            if (e.getKey().equals(key)) {
                return e;
            }
        }
        if (create) {
            Entry e = new Entry();
            e.setKey(key);
            m.getEntries().add(e);
            return e;
        } else {
            return null;
        }
    }

    private Map getOrCreatePropertiesMap() {
        Bean b = getSpringDataServiceManager();
        List<ConstructorArg> l = b.getConstructorArgs();
        Map rtn = null;
        if (l.size() < 4) {
            rtn = new Map();
            ConstructorArg a = new ConstructorArg();
            a.setMap(rtn);
            b.getMetasAndConstructorArgsAndProperties().add(a);
        } else {
            ConstructorArg a = l.get(4);
            rtn = a.getMap();
        }
        return rtn;
    }

    private void setMappings(List<String> l) {
        Property p = getMappingFilesProperty();

        Collections.sort(l);

        List<Object> l2 = new ArrayList<Object>();
        for (String s2 : l) {
            Value v = new Value();
            List<String> temp = new ArrayList<String>(1);
            temp.add(s2);
            v.setContent(temp);
            l2.add(v);
        }
        p.getList().setRefElement(l2);

        this.isDirty = true;
    }

    private void writeProps(Properties props) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataServiceUtils.writeProperties(props, bos, this.serviceId);
            this.fileService.writeFile(this.propertiesFile, bos.toString());
        } catch (IOException ex) {
            throw new ConfigurationException(ex);
        }
    }

    private void updateSpringConfigIfNecessary(Properties props) {
        if (Boolean.parseBoolean(props.getProperty("executeAs", "false"))) {
            Bean proxyBean;
            List<Bean> dsProxyBeans = this.beans.getBeansByType(SqlServerUserImpersonatingDataSourceProxy.class);
            if (dsProxyBeans.size() > 0) {
                proxyBean = dsProxyBeans.get(0);
            } else {
                Bean dsBean = this.beans.getBeanById(this.serviceId + "DataSource");
                dsBean.setId(this.serviceId + "TargetDataSource");

                proxyBean = new Bean();
                proxyBean.setId(this.serviceId + "DataSource");
                proxyBean.setClazz(SqlServerUserImpersonatingDataSourceProxy.class.getName());
                proxyBean.setLazyInit(DefaultableBoolean.TRUE);
                Property targetDataSourceProp = new Property();
                targetDataSourceProp.setName("targetDataSource");
                targetDataSourceProp.setRef(this.serviceId + "TargetDataSource");
                proxyBean.addProperty(targetDataSourceProp);
                this.beans.addBean(proxyBean);
                this.isDirty = true;
            }
            String adDomain = props.getProperty("activeDirectoryDomain", "");
            Property adDomainProp = proxyBean.getProperty("activeDirectoryDomain");
            if (adDomainProp == null) {
                adDomainProp = new Property();
                adDomainProp.setName("activeDirectoryDomain");
                adDomainProp.setValue("");
                proxyBean.addProperty(adDomainProp);
            }
            if (!adDomain.equals(adDomainProp.getValue())) {
                adDomainProp.setValue(adDomain);
                this.isDirty = true;
            }
        } else {
            List<Bean> dsProxyBeans = this.beans.getBeansByType(SqlServerUserImpersonatingDataSourceProxy.class);
            if (dsProxyBeans.size() > 0) {
                this.beans.removeBeanById(dsProxyBeans.get(0).getId());
                Bean dsBean = this.beans.getBeanById(this.serviceId + "TargetDataSource");
                dsBean.setId(this.serviceId + "DataSource");
                this.isDirty = true;
            }
        }
        write();
    }

    private Property getMappingFilesProperty() {
        return getSessionFactoryBean().getProperty(DataServiceConstants.SPRING_CFG_MAPPINGS_ATTR);
    }

    private Bean getSessionFactoryBean() {

        List<Bean> sessionFactoryBean = this.beans.getBeansByType(ConfigurationAndSessionFactoryBean.class.getName());

        // package rename
        if (sessionFactoryBean.isEmpty()) {
            sessionFactoryBean = this.beans.getBeansByType(DataServiceConstants.OLD_SESSION_FACTORY_CLASS_NAME);
        }

        if (sessionFactoryBean.isEmpty()) {
            throw new ConfigurationException(this.path + ": unable to find SessionFactory class \""
                + ConfigurationAndSessionFactoryBean.class.getName() + "\"");
        }

        return sessionFactoryBean.get(0);
    }

    private String getConnectionPropertiesFileName() {

        List<Bean> propertyPlaceholders = this.beans.getBeansByType(WMPropertyPlaceholderConfigurer.class.getName());

        // backward compat
        propertyPlaceholders.addAll(this.beans.getBeansByType(PropertyPlaceholderConfigurer.class.getName()));

        String rtn = null;

        // only support single prop file for now
        for (Bean b : propertyPlaceholders) {
            List<String> l = b.getProperty(DataServiceConstants.SPRING_CFG_LOCATIONS_ATTR).getListValue();
            for (String s : l) {
                rtn = StringUtils.appendPaths(this.rootPath, StringUtils.fromFirstOccurrence(s, "classpath:"));
            }
        }

        return rtn;
    }

    private Bean getSpringDataServiceManager() {
        List<Bean> l = this.beans.getBeansByType(SpringDataServiceManager.class.getName());
        if (l.isEmpty()) {
            // backward compat
            l = this.beans.getBeansByType(DataServiceConstants.OLD_SPRING_DATA_SERVICE_MANAGER_NAME);
        }
        if (l.size() != 1) {
            throw new SpringDataServiceManagerNotFound();
        }
        return l.get(0);
    }

    @SuppressWarnings("serial")
    private class SpringDataServiceManagerNotFound extends RuntimeException {

        SpringDataServiceManagerNotFound() {
            super("Could not find SpringDataServiceManager bean");
        }
    }
}
