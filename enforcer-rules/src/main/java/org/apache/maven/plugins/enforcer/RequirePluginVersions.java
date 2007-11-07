package org.apache.maven.plugins.enforcer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.maven.BuildFailureException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.Lifecycle;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.lifecycle.mapping.LifecycleMapping;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.InvalidPluginException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.version.PluginVersionNotFoundException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.plugins.enforcer.utils.EnforcerRuleUtils;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.ReflectionUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @version $Id$
 * 
 * This rule will enforce that all plugins specified in the
 * poms have a version declared.
 */
public class RequirePluginVersions
    implements EnforcerRule
{

    /**
     * The message to be printed in case the condition
     * returns <b>true</b>
     * 
     * @required
     * @parameter
     */
    public String message;

    /**
     * The message to be printed in case the condition
     * returns <b>true</b>
     * 
     * @required
     * @parameter
     */
    public boolean banLatest = true;

    /**
     * The message to be printed in case the condition
     * returns <b>true</b>
     * 
     * @required
     * @parameter
     */
    public boolean banRelease = true;

    private PluginManager pluginManager;

    private Map phaseToLifecycleMap;

    private List lifecycles;

    ArtifactFactory factory;

    ArtifactResolver resolver;

    ArtifactRepository local;

    List remoteRepositories;

    Log log;

    MavenSession session;

    EnforcerRuleUtils utils;
    
    public void execute ( EnforcerRuleHelper helper )
        throws EnforcerRuleException
    {
        log = helper.getLog();

        MavenProject project;
        try
        {
            // get the various expressions out of the
            // helper.
            project = (MavenProject) helper.evaluate( "${project}" );
            LifecycleExecutor life;
            life = (LifecycleExecutor) helper.getComponent( LifecycleExecutor.class );
            session = (MavenSession) helper.evaluate( "${session}" );
            pluginManager = (PluginManager) helper.getComponent( PluginManager.class );
            factory = (ArtifactFactory) helper.getComponent( ArtifactFactory.class );
            resolver = (ArtifactResolver) helper.getComponent( ArtifactResolver.class );
            local = (ArtifactRepository) helper.evaluate( "${localRepository}" );
            remoteRepositories = project.getRemoteArtifactRepositories();

            utils = new EnforcerRuleUtils( helper );
            
            // I couldn't find a direct way to get at the
            // lifecycles list.
            lifecycles = (List) ReflectionUtils.getValueIncludingSuperclasses( "lifecycles", life );

            // hardcoded for now
            Lifecycle lifecycle = getLifecycleForPhase( "deploy" );

            Set allPlugins = getAllPlugins( project, lifecycle );

            log.debug( "All Plugins: " + allPlugins );

            List plugins = getAllPluginEntries( project );

            // now look for the versions that aren't valid
            // and add to a list.
            ArrayList failures = new ArrayList();
            Iterator iter = allPlugins.iterator();
            while ( iter.hasNext() )
            {
                Plugin plugin = (Plugin) iter.next();
                if ( !hasVersionSpecified( plugin, plugins ) )
                {
                    failures.add( plugin );
                }
            }

            // if anything was found, log it then append the
            // optional message.
            if ( !failures.isEmpty() )
            {
                StringBuffer newMsg = new StringBuffer();
                newMsg.append( "Some plugins are missing valid versions:\n" );
                iter = failures.iterator();
                while ( iter.hasNext() )
                {
                    Plugin plugin = (Plugin) iter.next();
                    newMsg.append( plugin.getGroupId() + ":" + plugin.getArtifactId() + "\n" );
                }
                if ( StringUtils.isNotEmpty( message ) )
                {
                    newMsg.append( message );
                }

                throw new EnforcerRuleException( newMsg.toString() );
            }
        }
        catch ( ExpressionEvaluationException e )
        {
            throw new EnforcerRuleException( "Unable to Evaluate an Expression:" + e.getLocalizedMessage() );
        }
        catch ( ComponentLookupException e )
        {
            throw new EnforcerRuleException( "Unable to lookup a component:" + e.getLocalizedMessage() );
        }
        catch ( IllegalAccessException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( BuildFailureException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( LifecycleExecutionException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( PluginNotFoundException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( IOException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }
        catch ( XmlPullParserException e )
        {
            throw new EnforcerRuleException( e.getLocalizedMessage() );
        }

    }

    /*
     * Checks to see if the version is specified for the
     * plugin. Can optionally ban "RELEASE" or "LATEST" even
     * if specified.
     */
    protected boolean hasVersionSpecified ( Plugin source, List plugins )
    {
        boolean status = false;
        Iterator iter = plugins.iterator();
        while ( iter.hasNext() )
        {
            // find the matching plugin entry
            Plugin plugin = (Plugin) iter.next();
            if ( source.getArtifactId().equals( plugin.getArtifactId() )
                && source.getGroupId().equals( plugin.getGroupId() ) )
            {
                // found the entry. now see if the version
                // is specified
                if ( StringUtils.isNotEmpty( plugin.getVersion() ) )
                {
                    if ( banRelease && plugin.getVersion().equals( "RELEASE" ) )
                    {
                        return false;
                    }

                    if ( banLatest && plugin.getVersion().equals( "LATEST" ) )
                    {
                        return false;
                    }
                    // the version was specified and not
                    // banned. It's ok.
                    
                    status = true;  
                    
                    if (!banRelease && !banLatest)
                    {
                        //no need to keep looking
                        break;
                    }
                }
            }
        }
        return status;
    }

    /*
     * Uses borrowed lifecycle code to get a list of all
     * plugins bound to the lifecycle.
     */
    private Set getAllPlugins ( MavenProject project, Lifecycle lifecycle )
        throws PluginNotFoundException, LifecycleExecutionException

    {
        HashSet plugins = new HashSet();
        // first, bind those associated with the packaging
        Map mappings = findMappingsForLifecycle( project, lifecycle );

        Iterator iter = mappings.entrySet().iterator();
        while ( iter.hasNext() )
        {
            Entry entry = (Entry) iter.next();
            String value = (String) entry.getValue();
            String tokens[] = value.split( ":" );

            Plugin plugin = new Plugin();
            plugin.setGroupId( tokens[0] );
            plugin.setArtifactId( tokens[1] );
            plugins.add( plugin );
        }

        List mojos = findOptionalMojosForLifecycle( project, lifecycle );
        iter = mojos.iterator();
        while ( iter.hasNext() )
        {
            String value = (String) iter.next();
            String tokens[] = value.split( ":" );

            Plugin plugin = new Plugin();
            plugin.setGroupId( tokens[0] );
            plugin.setArtifactId( tokens[1] );
            plugins.add( plugin );
        }

        for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext(); )
        {
            plugins.add( i.next() );
        }

        return plugins;
    }

    /*
     * NOTE: All the code following this point was scooped
     * from the DefaultLifecycleExecutor. There must be a
     * better way but for now it should work.
     * 
     */
    public Map getPhaseToLifecycleMap ()
        throws LifecycleExecutionException
    {
        if ( phaseToLifecycleMap == null )
        {
            phaseToLifecycleMap = new HashMap();

            for ( Iterator i = lifecycles.iterator(); i.hasNext(); )
            {
                Lifecycle lifecycle = (Lifecycle) i.next();

                for ( Iterator p = lifecycle.getPhases().iterator(); p.hasNext(); )
                {
                    String phase = (String) p.next();

                    if ( phaseToLifecycleMap.containsKey( phase ) )
                    {
                        Lifecycle prevLifecycle = (Lifecycle) phaseToLifecycleMap.get( phase );
                        throw new LifecycleExecutionException( "Phase '" + phase
                            + "' is defined in more than one lifecycle: '" + lifecycle.getId() + "' and '"
                            + prevLifecycle.getId() + "'" );
                    }
                    else
                    {
                        phaseToLifecycleMap.put( phase, lifecycle );
                    }
                }
            }
        }
        return phaseToLifecycleMap;
    }

    private Lifecycle getLifecycleForPhase ( String phase )
        throws BuildFailureException, LifecycleExecutionException
    {
        Lifecycle lifecycle = (Lifecycle) getPhaseToLifecycleMap().get( phase );

        if ( lifecycle == null )
        {
            throw new BuildFailureException( "Unable to find lifecycle for phase '" + phase + "'" );
        }
        return lifecycle;
    }

    private Map findMappingsForLifecycle ( MavenProject project, Lifecycle lifecycle )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        String packaging = project.getPackaging();
        Map mappings = null;

        LifecycleMapping m = (LifecycleMapping) findExtension( project, LifecycleMapping.ROLE, packaging, session
            .getSettings(), session.getLocalRepository() );
        if ( m != null )
        {
            mappings = m.getPhases( lifecycle.getId() );
        }

        Map defaultMappings = lifecycle.getDefaultPhases();

        if ( mappings == null )
        {
            try
            {
                m = (LifecycleMapping) session.lookup( LifecycleMapping.ROLE, packaging );
                mappings = m.getPhases( lifecycle.getId() );
            }
            catch ( ComponentLookupException e )
            {
                if ( defaultMappings == null )
                {
                    throw new LifecycleExecutionException( "Cannot find lifecycle mapping for packaging: \'"
                        + packaging + "\'.", e );
                }
            }
        }

        if ( mappings == null )
        {
            if ( defaultMappings == null )
            {
                throw new LifecycleExecutionException( "Cannot find lifecycle mapping for packaging: \'" + packaging
                    + "\', and there is no default" );
            }
            else
            {
                mappings = defaultMappings;
            }
        }

        return mappings;
    }

    private List findOptionalMojosForLifecycle ( MavenProject project, Lifecycle lifecycle )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        String packaging = project.getPackaging();
        List optionalMojos = null;

        LifecycleMapping m = (LifecycleMapping) findExtension( project, LifecycleMapping.ROLE, packaging, session
            .getSettings(), session.getLocalRepository() );

        if ( m != null )
        {
            optionalMojos = m.getOptionalMojos( lifecycle.getId() );
        }

        if ( optionalMojos == null )
        {
            try
            {
                m = (LifecycleMapping) session.lookup( LifecycleMapping.ROLE, packaging );
                optionalMojos = m.getOptionalMojos( lifecycle.getId() );
            }
            catch ( ComponentLookupException e )
            {
                log.debug( "Error looking up lifecycle mapping to retrieve optional mojos. Lifecycle ID: "
                    + lifecycle.getId() + ". Error: " + e.getMessage(), e );
            }
        }

        if ( optionalMojos == null )
        {
            optionalMojos = Collections.EMPTY_LIST;
        }

        return optionalMojos;
    }

    private Object findExtension ( MavenProject project, String role, String roleHint, Settings settings,
                                   ArtifactRepository localRepository )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        Object pluginComponent = null;

        for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext() && pluginComponent == null; )
        {
            Plugin plugin = (Plugin) i.next();

            if ( plugin.isExtensions() )
            {
                verifyPlugin( plugin, project, settings, localRepository );

                // TODO: if moved to the plugin manager we
                // already have the descriptor from above
                // and so do can lookup the container
                // directly
                try
                {
                    pluginComponent = pluginManager.getPluginComponent( plugin, role, roleHint );
                }
                catch ( ComponentLookupException e )
                {
                    log.debug( "Unable to find the lifecycle component in the extension", e );
                }
                catch ( PluginManagerException e )
                {
                    throw new LifecycleExecutionException( "Error getting extensions from the plugin '"
                        + plugin.getKey() + "': " + e.getMessage(), e );
                }
            }
        }
        return pluginComponent;
    }

    private PluginDescriptor verifyPlugin ( Plugin plugin, MavenProject project, Settings settings,
                                            ArtifactRepository localRepository )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor;
        try
        {
            pluginDescriptor = pluginManager.verifyPlugin( plugin, project, settings, localRepository );
        }
        catch ( PluginManagerException e )
        {
            throw new LifecycleExecutionException( "Internal error in the plugin manager getting plugin '"
                + plugin.getKey() + "': " + e.getMessage(), e );
        }
        catch ( PluginVersionResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidPluginException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( PluginVersionNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        return pluginDescriptor;
    }

     /**
     * Gets all plugin entries in build.plugins or
     * build.pluginManagement.plugins in this project and
     * all parents
     * 
     * @param project
     * @return
     * @throws ArtifactResolutionException
     * @throws ArtifactNotFoundException
     * @throws IOException
     * @throws XmlPullParserException
     */
    protected List getAllPluginEntries ( MavenProject project )
        throws ArtifactResolutionException, ArtifactNotFoundException, IOException, XmlPullParserException
    {
        List plugins = new ArrayList();
        // get all the pom models
        List models = utils.getModelsRecursively( project.getGroupId(), project.getArtifactId(), project.getVersion(),
                                            new File( project.getBasedir(), "pom.xml" ) );

        // now find all the plugin entries, either in
        // build.plugins or build.pluginManagement.plugins
        Iterator iter = models.iterator();
        while ( iter.hasNext() )
        {
            Model model = (Model) iter.next();
            try
            {
                plugins.addAll( model.getBuild().getPlugins() );
            }
            catch ( NullPointerException e )
            {
                // guess there are no plugins here.
            }

            try
            {
                plugins.addAll( model.getBuild().getPluginManagement().getPlugins() );
            }
            catch ( NullPointerException e )
            {
                // guess there are no plugins here.
            }
        }

        return plugins;
    }


    /**
     * @return the banLatest
     */
    protected boolean isBanLatest ()
    {
        return this.banLatest;
    }

    /**
     * @param theBanLatest the banLatest to set
     */
    protected void setBanLatest ( boolean theBanLatest )
    {
        this.banLatest = theBanLatest;
    }

    /**
     * @return the banRelease
     */
    protected boolean isBanRelease ()
    {
        return this.banRelease;
    }

    /**
     * @param theBanRelease the banRelease to set
     */
    protected void setBanRelease ( boolean theBanRelease )
    {
        this.banRelease = theBanRelease;
    }

    /**
     * @return the message
     */
    protected String getMessage ()
    {
        return this.message;
    }

    /**
     * @param theMessage the message to set
     */
    protected void setMessage ( String theMessage )
    {
        this.message = theMessage;
    }

    /**
     * @return the utils
     */
    protected EnforcerRuleUtils getUtils ()
    {
        return this.utils;
    }

    /**
     * @param theUtils the utils to set
     */
    protected void setUtils ( EnforcerRuleUtils theUtils )
    {
        this.utils = theUtils;
    }
}