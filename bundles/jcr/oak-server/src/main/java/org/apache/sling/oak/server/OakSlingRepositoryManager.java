/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.sling.oak.server;

import static com.google.common.collect.ImmutableSet.of;
import static java.util.Collections.singleton;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.IndexUtils.createIndexDefinition;

import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.Privilege;
import javax.security.auth.login.Configuration;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.plugins.commit.ConflictValidatorProvider;
import org.apache.jackrabbit.oak.plugins.commit.JcrConflictHandler;
import org.apache.jackrabbit.oak.plugins.index.aggregate.AggregateIndexProvider;
import org.apache.jackrabbit.oak.plugins.index.aggregate.NodeAggregator;
import org.apache.jackrabbit.oak.plugins.index.aggregate.SimpleNodeAggregator;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProvider;
import org.apache.jackrabbit.oak.plugins.index.lucene.util.LuceneIndexHelper;
import org.apache.jackrabbit.oak.plugins.index.nodetype.NodeTypeIndexProvider;
import org.apache.jackrabbit.oak.plugins.index.property.PropertyIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.property.PropertyIndexProvider;
import org.apache.jackrabbit.oak.plugins.name.NameValidatorProvider;
import org.apache.jackrabbit.oak.plugins.name.NamespaceValidatorProvider;
import org.apache.jackrabbit.oak.plugins.nodetype.RegistrationEditorProvider;
import org.apache.jackrabbit.oak.plugins.nodetype.TypeEditorProvider;
import org.apache.jackrabbit.oak.plugins.nodetype.write.InitialContent;
import org.apache.jackrabbit.oak.plugins.version.VersionEditorProvider;
import org.apache.jackrabbit.oak.security.SecurityProviderImpl;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.lifecycle.RepositoryInitializer;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.authentication.ConfigurationUtil;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeConstants;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.security.user.UserConstants;
import org.apache.jackrabbit.oak.spi.security.user.action.AccessControlAction;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.whiteboard.OsgiWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.apache.jackrabbit.oak.spi.xml.ImportBehavior;
import org.apache.jackrabbit.oak.spi.xml.ProtectedItemImporter;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.jcr.api.NamespaceMapper;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.base.AbstractSlingRepository2;
import org.apache.sling.jcr.base.AbstractSlingRepositoryManager;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Sling repository implementation that wraps the Oak OSGi repository
 * implementation from the Oak project.
 */
@Component(
        immediate = true,
        metatype = true,
        name = "org.apache.sling.oak.server.OakSlingRepository",
        label = "Apache Sling Embedded JCR Repository (Oak)",
        description = "Configuration to launch an embedded JCR Repository "
            + "and provide it as a SlingRepository and a standard JCR "
            + "Repository. In addition, if the registration URL is not "
            + "empty, the repository is registered as defined.")
@Reference(
        name = "namespaceMapper",
        referenceInterface = NamespaceMapper.class,
        cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE,
        policy = ReferencePolicy.DYNAMIC)
public class OakSlingRepositoryManager extends AbstractSlingRepositoryManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // For backwards compatibility loginAdministrative is still enabled
    // In future releases, this default may change to false.
    public static final boolean DEFAULT_LOGIN_ADMIN_ENABLED = true;

    @Property(
            label = "Default Workspace",
            description = "Name of the workspace to use by default if not is given in any of the login methods. This name is used "
                + "to implement the SlingRepository.getDefaultWorkspace() "
                + "method. If this name is empty, a null value is used in "
                + "JCR calls so that the default workspace provided by the JCR repository is used.")
    public static final String PROPERTY_DEFAULT_WORKSPACE = "defaultWorkspace";

    @Property(
            boolValue = DEFAULT_LOGIN_ADMIN_ENABLED,
            label = "Enable Administrator Login",
            description = "Whether to enable or disable the SlingRepository.loginAdministrative "
                + "method. The default is 'true'. See "
                + "http://sling.apache.org/documentation/the-sling-engine/service-authentication.html "
                + "for information on deprecating and disabling the loginAdministrative method.")
    public static final String PROPERTY_LOGIN_ADMIN_ENABLED = "admin.login.enabled";

    public static final String DEFAULT_ADMIN_USER = "admin";

    @Property(
            value = DEFAULT_ADMIN_USER,
            label = "Administator",
            description = "The user name of the administrative user. This user"
                + "name is used to implement the SlingRepository.loginAdministrative(String)"
                + "method. It is intended for this user to provide full read/write access to repository.")
    public static final String PROPERTY_ADMIN_USER = "admin.name";

    @Reference
    private ServiceUserMapper serviceUserMapper;

    @Reference
    private NodeStore nodeStore;

    private ComponentContext componentContext;

    private Map<ServiceReference, NamespaceMapper> namespaceMapperRefs = new TreeMap<ServiceReference, NamespaceMapper>();

    private NamespaceMapper[] namespaceMappers;

    private String adminUserName;

    @Property(
            boolValue=true,
            label="Allow anonymous reads",
            description="If true, the anonymous user has read access to the whole repository (for backwards compatibility)")
    public static final String ANONYMOUS_READ_PROP = "anonymous.read.all";

    @Override
    protected ServiceUserMapper getServiceUserMapper() {
        return this.serviceUserMapper;
    }

    @Override
    protected NamespaceMapper[] getNamespaceMapperServices() {
        return this.namespaceMappers;
    }

    @Override
    protected Repository acquireRepository() {
        final SecurityProvider securityProvider = new SecurityProviderImpl(buildSecurityConfig());
        this.adminUserName = securityProvider.getConfiguration(UserConfiguration.class).getParameters().getConfigValue(
            UserConstants.PARAM_ADMIN_ID, UserConstants.DEFAULT_ADMIN_ID);

        final Whiteboard whiteboard = new OsgiWhiteboard(this.getComponentContext().getBundleContext());
        final Oak oak = new Oak(nodeStore)
        .with(new InitialContent())
        .with(new ExtraSlingContent())

        .with(JcrConflictHandler.JCR_CONFLICT_HANDLER)
        .with(new EditorHook(new VersionEditorProvider()))

        .with(securityProvider)

        .with(new NameValidatorProvider())
        .with(new NamespaceValidatorProvider())
        .with(new TypeEditorProvider())
        .with(new RegistrationEditorProvider())
        .with(new ConflictValidatorProvider())

        // index stuff
        .with(new PropertyIndexEditorProvider())

        .with(new PropertyIndexProvider())
        .with(new NodeTypeIndexProvider())

        .with(new LuceneIndexEditorProvider())
        .with(AggregateIndexProvider.wrap(new LuceneIndexProvider()
                .with(getNodeAggregator())))

        .with(getDefaultWorkspace())
        .withAsyncIndexing()
        .with(whiteboard)
        ;

        final ContentRepository contentRepository = oak.createContentRepository();
        return new JcrRepositoryHacks(contentRepository, whiteboard, securityProvider);
    }

    @Override
    protected void setup(BundleContext bundleContext, SlingRepository repository) {
        super.setup(bundleContext, repository);

        final Object o = this.getComponentContext().getProperties().get(ANONYMOUS_READ_PROP);
        if(o != null) {
            if(Boolean.valueOf(o.toString())) {
                log.warn("{} is true, granting anonymous user read access on /", ANONYMOUS_READ_PROP);
                Session s = null;
                try {
                    // TODO do we need to go via PrivilegeManager for the names? See OAK-1016 example.
                    s = repository.loginAdministrative(getDefaultWorkspace());
                    final String [] privileges = new String[] { Privilege.JCR_READ };
                    AccessControlUtils.addAccessControlEntry(
                            s,
                            "/",
                            EveryonePrincipal.getInstance(),
                            privileges,
                            true);
                    s.save();
                } catch (RepositoryException re) {
                    log.error("TODO: Failed setting up anonymous access", re);
                } finally {
                    if (s != null) {
                        s.logout();
                    }
                }
            } else {
                log.warn("TODO: should disable anonymous access when {} becomes false", ANONYMOUS_READ_PROP);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Dictionary<String, Object> getServiceRegistrationProperties() {
        return this.getComponentContext().getProperties();
    }

    @Override
    protected AbstractSlingRepository2 create(Bundle usingBundle) {
        return new OakSlingRepository(this, usingBundle, this.adminUserName);
    }

    @Override
    protected void destroy(AbstractSlingRepository2 repositoryServiceInstance) {
        // nothing to do, just drop the reference
    }

    @Override
    protected void disposeRepository(Repository repository) {
        ((JcrRepositoryHacks) repository).shutdown();
        this.adminUserName = null;
    }

    private ComponentContext getComponentContext() {
        return componentContext;
    }

    @Activate
    private void activate(ComponentContext componentContext) {
        // FIXME GRANITE-2315
        Configuration.setConfiguration(ConfigurationUtil.getJackrabbit2Configuration(ConfigurationParameters.EMPTY));
        this.componentContext = componentContext;

        @SuppressWarnings("unchecked")
        Dictionary<String, Object> properties = componentContext.getProperties();
        final String defaultWorkspace = PropertiesUtil.toString(properties.get(PROPERTY_DEFAULT_WORKSPACE), "oak.sling");
        final boolean disableLoginAdministrative = !PropertiesUtil.toBoolean(
            properties.get(PROPERTY_LOGIN_ADMIN_ENABLED), DEFAULT_LOGIN_ADMIN_ENABLED);

        this.adminUserName = PropertiesUtil.toString(properties.get(PROPERTY_ADMIN_USER), DEFAULT_ADMIN_USER);
        super.start(componentContext.getBundleContext(), defaultWorkspace, disableLoginAdministrative);
    }

    @Deactivate
    private void deactivate() {
        super.stop();
        this.componentContext = null;
        this.namespaceMapperRefs.clear();
        this.namespaceMappers = null;
    }

    @SuppressWarnings("unused")
    private void bindNamespaceMapper(final ServiceReference ref) {
        synchronized (this.namespaceMapperRefs) {
            this.namespaceMapperRefs.put(ref,
                (NamespaceMapper) this.getComponentContext().locateService("namespaceMapper", ref));
            this.namespaceMappers = this.namespaceMapperRefs.values().toArray(
                new NamespaceMapper[this.namespaceMapperRefs.values().size()]);
        }
    }

    @SuppressWarnings("unused")
    private void unbindNamespaceMapper(final ServiceReference ref) {
        synchronized (this.namespaceMapperRefs) {
            this.namespaceMapperRefs.remove(ref);
            this.namespaceMappers = this.namespaceMapperRefs.values().toArray(
                new NamespaceMapper[this.namespaceMapperRefs.values().size()]);
        }
    }

    private static NodeAggregator getNodeAggregator() {
        return new SimpleNodeAggregator()
            .newRuleWithName("nt:file", Arrays.asList(new String [] {"jcr:content"}))
            ;
    }


    private static final class ExtraSlingContent implements RepositoryInitializer {


        @Override
        public void initialize(NodeBuilder root) {
            if (root.hasChildNode(INDEX_DEFINITIONS_NAME)) {
                NodeBuilder index = root.child(INDEX_DEFINITIONS_NAME);

                // jcr:
                property(index, "jcrLanguage", "jcr:language");
                property(index, "jcrLockOwner", "jcr:lockOwner");

                // sling:
                property(index, "slingAlias", "sling:alias");
                property(index, "slingResource", "sling:resource");
                property(index, "slingResourceType", "sling:resourceType");
                property(index, "slingVanityPath", "sling:vanityPath");

                // various
                property(index, "event.job.topic", "event.job.topic");
                property(index, "extensionType", "extensionType");
                property(index, "lockCreated", "lock.created");
                property(index, "status", "status");
                property(index, "type", "type");

                // lucene full-text index
                if (!index.hasChildNode("lucene")) {
                    LuceneIndexHelper.newLuceneIndexDefinition(
                            index, "lucene", LuceneIndexHelper.JR_PROPERTY_INCLUDES,
                            of(
                               "jcr:createdBy",
                               "jcr:lastModifiedBy",
                               "sling:alias",
                               "sling:resourceType",
                               "sling:vanityPath"),
                            "async");
                }

            }
        }

        /**
         * A convenience method to create a non-unique property index.
         */
        private static void property(NodeBuilder index, String indexName, String propertyName) {
            if (!index.hasChildNode(indexName)) {
                createIndexDefinition(index, indexName, true, false, singleton(propertyName), null);
            }
        }

    }

    // TODO: use proper osgi configuration (once that works in oak)
    private static ConfigurationParameters buildSecurityConfig() {
        Map<String, Object> userConfig = new HashMap<String, Object>();
        userConfig.put(UserConstants.PARAM_GROUP_PATH, "/home/groups");
        userConfig.put(UserConstants.PARAM_USER_PATH, "/home/users");
        userConfig.put(UserConstants.PARAM_DEFAULT_DEPTH, 1);
        userConfig.put(AccessControlAction.USER_PRIVILEGE_NAMES, new String[] { PrivilegeConstants.JCR_ALL });
        userConfig.put(AccessControlAction.GROUP_PRIVILEGE_NAMES, new String[] { PrivilegeConstants.JCR_READ });
        userConfig.put(ProtectedItemImporter.PARAM_IMPORT_BEHAVIOR, ImportBehavior.NAME_BESTEFFORT);

        Map<String, Object> config = new HashMap<String, Object>();
        config.put(
                UserConfiguration.NAME,
                new ConfigurationParameters(userConfig));
        return new ConfigurationParameters(config);
    }

}