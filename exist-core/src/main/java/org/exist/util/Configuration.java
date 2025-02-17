/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.util;

import com.evolvedbinary.j8fu.tuple.Tuple2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.exist.backup.SystemExport;
import org.exist.collections.CollectionCache;
import org.exist.repo.Deployment;

import org.exist.resolver.ResolverFactory;
import org.exist.start.Main;
import org.exist.storage.lock.LockManager;
import org.exist.storage.lock.LockTable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import org.exist.Indexer;
import org.exist.indexing.IndexManager;
import org.exist.dom.memtree.SAXAdapter;
import org.exist.scheduler.JobConfig;
import org.exist.scheduler.JobException;
import org.exist.storage.BrokerFactory;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.DefaultCacheManager;
import org.exist.storage.IndexSpec;
import org.exist.storage.NativeBroker;
import org.exist.storage.NativeValueIndex;
import org.exist.storage.XQueryPool;
import org.exist.storage.journal.Journal;
import org.exist.storage.serializers.CustomMatchListenerFactory;
import org.exist.storage.serializers.Serializer;
import org.exist.validation.GrammarPool;
import org.exist.xmldb.DatabaseImpl;
import org.exist.xquery.FunctionFactory;
import org.exist.xquery.PerformanceStats;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.XQueryWatchDog;
import org.exist.xslt.TransformerFactoryAllocator;

import java.io.IOException;
import java.io.InputStream;

import java.net.MalformedURLException;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.exist.Namespaces;
import org.exist.scheduler.JobType;
import org.exist.xquery.Module;
import org.xmlresolver.Resolver;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;
import static javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING;


public class Configuration implements ErrorHandler
{
    private final static Logger       LOG            = LogManager.getLogger(Configuration.class); //Logger
    protected Optional<Path>          configFilePath = Optional.empty();
    protected Optional<Path>          existHome      = Optional.empty();

    protected DocumentBuilder         builder        = null;
    protected HashMap<String, Object> config         = new HashMap<>(); //Configuration

    private static final String XQUERY_CONFIGURATION_ELEMENT_NAME = "xquery";
    private static final String XQUERY_BUILTIN_MODULES_CONFIGURATION_MODULES_ELEMENT_NAME = "builtin-modules";
    private static final String XQUERY_BUILTIN_MODULES_CONFIGURATION_MODULE_ELEMENT_NAME = "module";
    
    public final static String BINARY_CACHE_CLASS_PROPERTY = "binary.cache.class";
    
    public Configuration() throws DatabaseConfigurationException {
        this(DatabaseImpl.CONF_XML, Optional.empty());
    }


    public Configuration(final String configFilename) throws DatabaseConfigurationException {
        this(configFilename, Optional.empty());
    }


    public Configuration(String configFilename, Optional<Path> existHomeDirname) throws DatabaseConfigurationException {
        InputStream is = null;
        try {

            if(configFilename == null) {
                // Default file name
                configFilename = DatabaseImpl.CONF_XML;
            }

            // firstly, try to read the configuration from a file within the
            // classpath
            try {
                is = Configuration.class.getClassLoader().getResourceAsStream(configFilename);

                if(is != null) {
                    LOG.info("Reading configuration from classloader");
                    configFilePath = Optional.of(Paths.get(Configuration.class.getClassLoader().getResource(configFilename).toURI()));
                }
            } catch(final Exception e) {
                // EB: ignore and go forward, e.g. in case there is an absolute
                // file name for configFileName
                LOG.debug( e );
            }

            // otherwise, secondly try to read configuration from file. Guess the
            // location if necessary
            if(is == null) {
                existHome = existHomeDirname.map(Optional::of).orElse(ConfigurationHelper.getExistHome(configFilename));

                if(!existHome.isPresent()) {

                    // EB: try to create existHome based on location of config file
                    // when config file points to absolute file location
                    final Path absoluteConfigFile = Paths.get(configFilename);

                    if(absoluteConfigFile.isAbsolute() && Files.exists(absoluteConfigFile) && Files.isReadable(absoluteConfigFile)) {
                        existHome = Optional.of(absoluteConfigFile.getParent());
                        configFilename = FileUtils.fileName(absoluteConfigFile);
                    }
                }


                Path configFile = Paths.get(configFilename);

                if(!configFile.isAbsolute() && existHome.isPresent()) {

                    // try the passed or constructed existHome first
                    configFile = existHome.get().resolve(configFilename);

                    if (!Files.exists(configFile)) {
                        configFile = existHome.get().resolve(Main.CONFIG_DIR_NAME).resolve(configFilename);
                    }
                }

                //if( configFile == null ) {
                //    configFile = ConfigurationHelper.lookup( configFilename );
                //}

                if(!Files.exists(configFile) || !Files.isReadable(configFile)) {
                    throw new DatabaseConfigurationException("Unable to read configuration file at " + configFile);
                }
                
                configFilePath = Optional.of(configFile.toAbsolutePath());
                is = Files.newInputStream(configFile);
            }

            LOG.info("Reading configuration from file {}", configFilePath.map(Path::toString).orElse("Unknown"));

            // set dbHome to parent of the conf file found, to resolve relative
            // path from conf file
            existHomeDirname = configFilePath.map(Path::getParent);

            // initialize xml parser
            // we use eXist's in-memory DOM implementation to work
            // around a bug in Xerces
            final SAXParserFactory factory = ExistSAXParserFactory.getSAXParserFactory();
            factory.setNamespaceAware(true);

//            factory.setFeature("http://apache.org/xml/features/validation/schema", true);
//            factory.setFeature("http://apache.org/xml/features/validation/dynamic", true);
            final InputSource src = new InputSource(is);
            final SAXParser parser = factory.newSAXParser();
            final XMLReader reader = parser.getXMLReader();

            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature(FEATURE_SECURE_PROCESSING, true);

            final SAXAdapter adapter = new SAXAdapter();
            reader.setContentHandler(adapter);
            reader.setProperty(Namespaces.SAX_LEXICAL_HANDLER, adapter);
            reader.parse(src);

            final Document doc = adapter.getDocument();

            //indexer settings
            final NodeList indexers = doc.getElementsByTagName(Indexer.CONFIGURATION_ELEMENT_NAME);
            if(indexers.getLength() > 0) {
                configureIndexer( existHomeDirname, doc, (Element)indexers.item( 0 ) );
            }

            //scheduler settings
            final NodeList schedulers = doc.getElementsByTagName(JobConfig.CONFIGURATION_ELEMENT_NAME);
            if(schedulers.getLength() > 0) {
                configureScheduler((Element)schedulers.item(0));
            }

            //db connection settings
            final NodeList dbcon = doc.getElementsByTagName(BrokerPool.CONFIGURATION_CONNECTION_ELEMENT_NAME);
            if(dbcon.getLength() > 0) {
                configureBackend(existHomeDirname, (Element)dbcon.item(0));
            }

            // lock-table settings
            final NodeList lockManager = doc.getElementsByTagName("lock-manager");
            if(lockManager.getLength() > 0) {
                configureLockManager((Element) lockManager.item(0));
            }

            // repository settings
            final NodeList repository = doc.getElementsByTagName("repository");
            if(repository.getLength() > 0) {
                configureRepository((Element) repository.item(0));
            }

            // binary manager settings
            final NodeList binaryManager = doc.getElementsByTagName("binary-manager");
            if(binaryManager.getLength() > 0) {
                configureBinaryManager((Element)binaryManager.item(0));
            }
            
            //transformer settings
            final NodeList transformers = doc.getElementsByTagName(TransformerFactoryAllocator.CONFIGURATION_ELEMENT_NAME);
            if( transformers.getLength() > 0 ) {
                configureTransformer((Element)transformers.item(0));
            }

            //parser settings
            final NodeList parsers = doc.getElementsByTagName(HtmlToXmlParser.PARSER_ELEMENT_NAME);
            if(parsers.getLength() > 0) {
                configureParser((Element)parsers.item(0));
            }

            //serializer settings
            final NodeList serializers = doc.getElementsByTagName(Serializer.CONFIGURATION_ELEMENT_NAME);
            if(serializers.getLength() > 0) {
                configureSerializer((Element)serializers.item(0));
            }

            //XUpdate settings
            final NodeList xupdates = doc.getElementsByTagName(DBBroker.CONFIGURATION_ELEMENT_NAME);
            if(xupdates.getLength() > 0) {
                configureXUpdate((Element)xupdates.item(0));
            }

            //XQuery settings
            final NodeList xquery = doc.getElementsByTagName(XQUERY_CONFIGURATION_ELEMENT_NAME);
            if(xquery.getLength() > 0) {
                configureXQuery((Element)xquery.item(0));
            }

            //Validation
            final NodeList validations = doc.getElementsByTagName(XMLReaderObjectFactory.CONFIGURATION_ELEMENT_NAME);
            if(validations.getLength() > 0) {
                configureValidation(existHomeDirname, (Element)validations.item(0));
            }

        }
        catch(final SAXException | IOException | ParserConfigurationException e) {
            LOG.error("error while reading config file: {}", configFilename, e);
            throw new DatabaseConfigurationException(e.getMessage(), e);
        } finally {
            if(is != null) {
                try {
                    is.close();
                } catch(final IOException ioe) {
                    LOG.error(ioe);
                }
            }
        }
    }

    private void configureLockManager(final Element lockManager) throws DatabaseConfigurationException {
        final boolean upgradeCheck = parseBoolean(getConfigAttributeValue(lockManager, "upgrade-check"), false);
        final boolean warnWaitOnReadForWrite = parseBoolean(getConfigAttributeValue(lockManager, "warn-wait-on-read-for-write"), false);
        final boolean pathsMultiWriter = parseBoolean(getConfigAttributeValue(lockManager, "paths-multi-writer"), false);

        config.put(LockManager.CONFIGURATION_UPGRADE_CHECK, upgradeCheck);
        config.put(LockManager.CONFIGURATION_WARN_WAIT_ON_READ_FOR_WRITE, warnWaitOnReadForWrite);
        config.put(LockManager.CONFIGURATION_PATHS_MULTI_WRITER, pathsMultiWriter);

        final NodeList nlLockTable = lockManager.getElementsByTagName("lock-table");
        if(nlLockTable.getLength() > 0) {
            final Element lockTable = (Element)nlLockTable.item(0);
            final boolean lockTableDisabled = parseBoolean(getConfigAttributeValue(lockTable, "disabled"), false);
            final int lockTableTraceStackDepth = parseInt(getConfigAttributeValue(lockTable, "trace-stack-depth"), 0);

            config.put(LockTable.CONFIGURATION_DISABLED, lockTableDisabled);
            config.put(LockTable.CONFIGURATION_TRACE_STACK_DEPTH, lockTableTraceStackDepth);
        }

        final NodeList nlDocument = lockManager.getElementsByTagName("document");
        if(nlDocument.getLength() > 0) {
            final Element document = (Element)nlDocument.item(0);
            final boolean documentUsePathLocks = parseBoolean(getConfigAttributeValue(document, "use-path-locks"), false);

            config.put(LockManager.CONFIGURATION_PATH_LOCKS_FOR_DOCUMENTS, documentUsePathLocks);
        }
    }

    private void configureRepository(Element element) {
        String root = getConfigAttributeValue(element, "root");
        if (root != null && !root.isEmpty()) {
            if (!root.endsWith("/"))
                {root += "/";}
            config.put(Deployment.PROPERTY_APP_ROOT, root);
        }
    }

    private void configureBinaryManager(Element binaryManager) throws DatabaseConfigurationException {
        final NodeList nlCache = binaryManager.getElementsByTagName("cache");
        if(nlCache.getLength() > 0) {
            final Element cache = (Element)nlCache.item(0);
            final String binaryCacheClass = getConfigAttributeValue(cache, "class");
            config.put(BINARY_CACHE_CLASS_PROPERTY, binaryCacheClass);
            LOG.debug(BINARY_CACHE_CLASS_PROPERTY + ": {}", config.get(BINARY_CACHE_CLASS_PROPERTY));
        }
    }
    
    private void configureXQuery( Element xquery ) throws DatabaseConfigurationException
    {
        //java binding
        final String javabinding = getConfigAttributeValue( xquery, FunctionFactory.ENABLE_JAVA_BINDING_ATTRIBUTE );

        if( javabinding != null ) {
            config.put( FunctionFactory.PROPERTY_ENABLE_JAVA_BINDING, javabinding );
            LOG.debug(FunctionFactory.PROPERTY_ENABLE_JAVA_BINDING + ": {}", config.get(FunctionFactory.PROPERTY_ENABLE_JAVA_BINDING));
        }

        final String disableDeprecated = getConfigAttributeValue( xquery, FunctionFactory.DISABLE_DEPRECATED_FUNCTIONS_ATTRIBUTE );
        config.put( FunctionFactory.PROPERTY_DISABLE_DEPRECATED_FUNCTIONS, Configuration.parseBoolean( disableDeprecated, FunctionFactory.DISABLE_DEPRECATED_FUNCTIONS_BY_DEFAULT ) );
        LOG.debug(FunctionFactory.PROPERTY_DISABLE_DEPRECATED_FUNCTIONS + ": {}", config.get(FunctionFactory.PROPERTY_DISABLE_DEPRECATED_FUNCTIONS));

        final String optimize = getConfigAttributeValue( xquery, XQueryContext.ENABLE_QUERY_REWRITING_ATTRIBUTE );

        if( ( optimize != null ) && (!optimize.isEmpty()) ) {
            config.put( XQueryContext.PROPERTY_ENABLE_QUERY_REWRITING, optimize );
            LOG.debug(XQueryContext.PROPERTY_ENABLE_QUERY_REWRITING + ": {}", config.get(XQueryContext.PROPERTY_ENABLE_QUERY_REWRITING));
        }

        final String enforceIndexUse = getConfigAttributeValue( xquery, XQueryContext.ENFORCE_INDEX_USE_ATTRIBUTE );
        if (enforceIndexUse != null) {
        	config.put( XQueryContext.PROPERTY_ENFORCE_INDEX_USE, enforceIndexUse );
        }
        
        final String backwardCompatible = getConfigAttributeValue( xquery, XQueryContext.XQUERY_BACKWARD_COMPATIBLE_ATTRIBUTE );

        if( ( backwardCompatible != null ) && (!backwardCompatible.isEmpty()) ) {
            config.put( XQueryContext.PROPERTY_XQUERY_BACKWARD_COMPATIBLE, backwardCompatible );
            LOG.debug(XQueryContext.PROPERTY_XQUERY_BACKWARD_COMPATIBLE + ": {}", config.get(XQueryContext.PROPERTY_XQUERY_BACKWARD_COMPATIBLE));
        }

        final String raiseErrorOnFailedRetrieval = getConfigAttributeValue( xquery, XQueryContext.XQUERY_RAISE_ERROR_ON_FAILED_RETRIEVAL_ATTRIBUTE );
        config.put( XQueryContext.PROPERTY_XQUERY_RAISE_ERROR_ON_FAILED_RETRIEVAL, Configuration.parseBoolean( raiseErrorOnFailedRetrieval, XQueryContext.XQUERY_RAISE_ERROR_ON_FAILED_RETRIEVAL_DEFAULT ) );
        LOG.debug(XQueryContext.PROPERTY_XQUERY_RAISE_ERROR_ON_FAILED_RETRIEVAL + ": {}", config.get(XQueryContext.PROPERTY_XQUERY_RAISE_ERROR_ON_FAILED_RETRIEVAL));

        final String trace = getConfigAttributeValue( xquery, PerformanceStats.CONFIG_ATTR_TRACE );
        config.put( PerformanceStats.CONFIG_PROPERTY_TRACE, trace );

        // built-in-modules
        final Map<String, Class<?>> classMap      = new HashMap<>();
        final Map<String, String>   knownMappings = new HashMap<>();
        final Map<String, Map<String, List<? extends Object>>> moduleParameters = new HashMap<>();
        loadModuleClasses(xquery, classMap, knownMappings, moduleParameters);
        config.put( XQueryContext.PROPERTY_BUILT_IN_MODULES, classMap);
        config.put( XQueryContext.PROPERTY_STATIC_MODULE_MAP, knownMappings);
        config.put( XQueryContext.PROPERTY_MODULE_PARAMETERS, moduleParameters);
    }

    /**
     * Read list of built-in modules from the configuration. This method will only make sure
     * that the specified module class exists and is a subclass of {@link org.exist.xquery.Module}.
     *
     * @param   xquery            configuration root
     * @param   modulesClassMap   map containing all classes of modules
     * @param   modulesSourceMap  map containing all source uris to external resources
     *
     * @throws  DatabaseConfigurationException
     */
    private void loadModuleClasses( Element xquery, Map<String, Class<?>> modulesClassMap, Map<String, String> modulesSourceMap, Map<String, Map<String, List<? extends Object>>> moduleParameters) throws DatabaseConfigurationException {
        // add the standard function module
        modulesClassMap.put(Namespaces.XPATH_FUNCTIONS_NS, org.exist.xquery.functions.fn.FnModule.class);

        // add other modules specified in configuration
        final NodeList builtins = xquery.getElementsByTagName(XQUERY_BUILTIN_MODULES_CONFIGURATION_MODULES_ELEMENT_NAME);

        // search under <builtin-modules>
        if(builtins.getLength() > 0) {
            Element  elem    = (Element)builtins.item(0);
            final NodeList modules = elem.getElementsByTagName(XQUERY_BUILTIN_MODULES_CONFIGURATION_MODULE_ELEMENT_NAME);

            if(modules.getLength() > 0) {

                // iterate over all <module src= uri= class=> entries
                for(int i = 0; i < modules.getLength(); i++) {

                    // Get element.
                    elem = (Element)modules.item(i);

                    // Get attributes uri class and src
                    final String uri    = elem.getAttribute(XQueryContext.BUILT_IN_MODULE_URI_ATTRIBUTE);
                    final String clazz  = elem.getAttribute(XQueryContext.BUILT_IN_MODULE_CLASS_ATTRIBUTE);
                    final String source = elem.getAttribute(XQueryContext.BUILT_IN_MODULE_SOURCE_ATTRIBUTE);

                    // uri attribute is the identifier and is always required
                    if(uri == null) {
                        throw(new DatabaseConfigurationException("element 'module' requires an attribute 'uri'" ));
                    }

                    // either class or source attribute must be present
                    if((clazz == null) && (source == null)) {
                        throw(new DatabaseConfigurationException("element 'module' requires either an attribute " + "'class' or 'src'" ));
                    }

                    if(source != null) {
                        // Store src attribute info

                        modulesSourceMap.put(uri, source);

                        if(LOG.isDebugEnabled()) {
                            LOG.debug("Registered mapping for module '{}' to '{}'", uri, source);
                        }

                    } else {
                        // source class attribute info

                        // Get class of module
                        final Class<?> moduleClass = lookupModuleClass(uri, clazz);

                        // Store class if thw module class actually exists
                        if( moduleClass != null) {
                            modulesClassMap.put(uri, moduleClass);
                        }

                        if(LOG.isDebugEnabled()) {
                            LOG.debug("Configured module '{}' implemented in '{}'", uri, clazz);
                        }
                    }

                    //parse any module parameters
                    moduleParameters.put(uri, ParametersExtractor.extract(elem.getElementsByTagName(ParametersExtractor.PARAMETER_ELEMENT_NAME)));
                }
            }
        }
    }

    /**
     *  Returns the Class object associated with the with the given module class name. All
     * important exceptions are caught. @see org.exist.xquery.Module
     *
     * @param uri   namespace of class. For logging purposes only.
     * @param clazz the fully qualified name of the desired module class.
     * @return      the module Class object for the module with the specified name.
     * @throws      DatabaseConfigurationException if the given module class is not an instance
     *              of org.exist.xquery.Module
     */
    private Class<?> lookupModuleClass(String uri, String clazz) throws DatabaseConfigurationException
    {
        Class<?> mClass = null;

        try {
            mClass = Class.forName( clazz );

            if( !( Module.class.isAssignableFrom( mClass ) ) ) {
                throw( new DatabaseConfigurationException( "Failed to load module: " + uri
                        + ". Class " + clazz + " is not an instance of org.exist.xquery.Module." ) );
            }

        } catch( final ClassNotFoundException e ) {

            // Note: can't throw an exception here since this would create
            // problems with test cases and jar dependencies
            LOG.error("Configuration problem: class not found for module '{}' (ClassNotFoundException); class:'{}'; message:'{}'", uri, clazz, e.getMessage());

        } catch( final NoClassDefFoundError e ) {
            LOG.error("Module {} could not be initialized due to a missing dependancy (NoClassDefFoundError): {}", uri, e.getMessage(), e);
        }

        return mClass;
    }

    /**
     * DOCUMENT ME!
     *
     * @param   xupdate
     *
     * @throws  NumberFormatException
     */
    private void configureXUpdate( Element xupdate ) throws NumberFormatException
    {
        final String fragmentation = getConfigAttributeValue( xupdate, DBBroker.XUPDATE_FRAGMENTATION_FACTOR_ATTRIBUTE );

        if( fragmentation != null ) {
            config.put( DBBroker.PROPERTY_XUPDATE_FRAGMENTATION_FACTOR, Integer.valueOf(fragmentation) );
            LOG.debug(DBBroker.PROPERTY_XUPDATE_FRAGMENTATION_FACTOR + ": {}", config.get(DBBroker.PROPERTY_XUPDATE_FRAGMENTATION_FACTOR));
        }

        final String consistencyCheck = getConfigAttributeValue( xupdate, DBBroker.XUPDATE_CONSISTENCY_CHECKS_ATTRIBUTE );

        if( consistencyCheck != null ) {
            config.put( DBBroker.PROPERTY_XUPDATE_CONSISTENCY_CHECKS, parseBoolean( consistencyCheck, false ) );
            LOG.debug(DBBroker.PROPERTY_XUPDATE_CONSISTENCY_CHECKS + ": {}", config.get(DBBroker.PROPERTY_XUPDATE_CONSISTENCY_CHECKS));
        }
    }


    private void configureTransformer( Element transformer )
    {
        final String className = getConfigAttributeValue( transformer, TransformerFactoryAllocator.TRANSFORMER_CLASS_ATTRIBUTE );

        if( className != null ) {
            config.put( TransformerFactoryAllocator.PROPERTY_TRANSFORMER_CLASS, className );
            LOG.debug(TransformerFactoryAllocator.PROPERTY_TRANSFORMER_CLASS + ": {}", config.get(TransformerFactoryAllocator.PROPERTY_TRANSFORMER_CLASS));

            // Process any specified attributes that should be passed to the transformer factory

            final NodeList                  attrs      = transformer.getElementsByTagName( TransformerFactoryAllocator.CONFIGURATION_TRANSFORMER_ATTRIBUTE_ELEMENT_NAME );
            final Hashtable<Object, Object> attributes = new Properties();

            for( int a = 0; a < attrs.getLength(); a++ ) {
                final Element attr  = (Element)attrs.item( a );
                final String  name  = attr.getAttribute( "name" );
                final String  value = attr.getAttribute( "value" );
                final String  type  = attr.getAttribute( "type" );

                if( ( name == null ) || (name.isEmpty()) ) {
                    LOG.warn("Discarded invalid attribute for TransformerFactory: '{}', name not specified", className);

                } else if( ( type == null ) || (type.isEmpty()) || type.equalsIgnoreCase( "string" ) ) {
                    attributes.put( name, value );

                } else if( type.equalsIgnoreCase( "boolean" ) ) {
                    attributes.put( name, Boolean.valueOf( value ) );

                } else if( type.equalsIgnoreCase( "integer" ) ) {

                    try {
                        attributes.put( name, Integer.valueOf( value ) );
                    }
                    catch( final NumberFormatException nfe ) {
                        LOG.warn("Discarded invalid attribute for TransformerFactory: '{}', name: {}, value not integer: {}", className, name, value, nfe);
                    }

                } else {

                    // Assume string type
                    attributes.put( name, value );
                }
            }

            config.put( TransformerFactoryAllocator.PROPERTY_TRANSFORMER_ATTRIBUTES, attributes );
        }

        final String cachingValue = getConfigAttributeValue( transformer, TransformerFactoryAllocator.TRANSFORMER_CACHING_ATTRIBUTE );

        if( cachingValue != null ) {
            config.put( TransformerFactoryAllocator.PROPERTY_CACHING_ATTRIBUTE, parseBoolean( cachingValue, false ) );
            LOG.debug(TransformerFactoryAllocator.PROPERTY_CACHING_ATTRIBUTE + ": {}", config.get(TransformerFactoryAllocator.PROPERTY_CACHING_ATTRIBUTE));
        }
    }

    private void configureParser(final Element parser) {
        configureXmlParser(parser);
        configureHtmlToXmlParser(parser);
    }

    private void configureXmlParser(final Element parser) {
        final NodeList nlXml = parser.getElementsByTagName(XMLReaderPool.XmlParser.XML_PARSER_ELEMENT);
        if(nlXml.getLength() > 0) {
            final Element xml = (Element)nlXml.item(0);

            final NodeList nlFeatures = xml.getElementsByTagName(XMLReaderPool.XmlParser.XML_PARSER_FEATURES_ELEMENT);
            if(nlFeatures.getLength() > 0) {
                final Properties pFeatures = ParametersExtractor.parseFeatures(nlFeatures.item(0));
                if(pFeatures != null) {
                    final Map<String, Boolean> features = new HashMap<>();
                    pFeatures.forEach((k,v) -> features.put(k.toString(), Boolean.valueOf(v.toString())));
                    config.put(XMLReaderPool.XmlParser.XML_PARSER_FEATURES_PROPERTY, features);
                }
            }
        }
    }

    private void configureHtmlToXmlParser(final Element parser) {
        final NodeList nlHtmlToXml = parser.getElementsByTagName(HtmlToXmlParser.HTML_TO_XML_PARSER_ELEMENT);
        if(nlHtmlToXml.getLength() > 0) {
            final Element htmlToXml = (Element)nlHtmlToXml.item(0);
            final String htmlToXmlParserClass = getConfigAttributeValue(htmlToXml, HtmlToXmlParser.HTML_TO_XML_PARSER_CLASS_ATTRIBUTE);
            config.put(HtmlToXmlParser.HTML_TO_XML_PARSER_PROPERTY, htmlToXmlParserClass);

            final NodeList nlProperties = htmlToXml.getElementsByTagName(HtmlToXmlParser.HTML_TO_XML_PARSER_PROPERTIES_ELEMENT);
            if(nlProperties.getLength() > 0) {
                final Properties pProperties = ParametersExtractor.parseProperties(nlProperties.item(0));
                if(pProperties != null) {
                    final Map<String, Object> properties = new HashMap<>();
                    pProperties.forEach((k,v) -> properties.put(k.toString(), v));
                    config.put(HtmlToXmlParser.HTML_TO_XML_PARSER_PROPERTIES_PROPERTY, properties);
                }
            }

            final NodeList nlFeatures = htmlToXml.getElementsByTagName(HtmlToXmlParser.HTML_TO_XML_PARSER_FEATURES_ELEMENT);
            if(nlFeatures.getLength() > 0) {
                final Properties pFeatures = ParametersExtractor.parseFeatures(nlFeatures.item(0));
                if(pFeatures != null) {
                    final Map<String, Boolean> features = new HashMap<>();
                    pFeatures.forEach((k,v) -> features.put(k.toString(), Boolean.valueOf(v.toString())));
                    config.put(HtmlToXmlParser.HTML_TO_XML_PARSER_FEATURES_PROPERTY, features);
                }
            }
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @param  serializer
     */
    private void configureSerializer( Element serializer )
    {
        final String xinclude = getConfigAttributeValue( serializer, Serializer.ENABLE_XINCLUDE_ATTRIBUTE );

        if( xinclude != null ) {
            config.put( Serializer.PROPERTY_ENABLE_XINCLUDE, xinclude );
            LOG.debug(Serializer.PROPERTY_ENABLE_XINCLUDE + ": {}", config.get(Serializer.PROPERTY_ENABLE_XINCLUDE));
        }

        final String xsl = getConfigAttributeValue( serializer, Serializer.ENABLE_XSL_ATTRIBUTE );

        if( xsl != null ) {
            config.put( Serializer.PROPERTY_ENABLE_XSL, xsl );
            LOG.debug(Serializer.PROPERTY_ENABLE_XSL + ": {}", config.get(Serializer.PROPERTY_ENABLE_XSL));
        }

        final String indent = getConfigAttributeValue( serializer, Serializer.INDENT_ATTRIBUTE );

        if( indent != null ) {
            config.put( Serializer.PROPERTY_INDENT, indent );
            LOG.debug(Serializer.PROPERTY_INDENT + ": {}", config.get(Serializer.PROPERTY_INDENT));
        }

        final String compress = getConfigAttributeValue( serializer, Serializer.COMPRESS_OUTPUT_ATTRIBUTE );

        if( compress != null ) {
            config.put( Serializer.PROPERTY_COMPRESS_OUTPUT, compress );
            LOG.debug(Serializer.PROPERTY_COMPRESS_OUTPUT + ": {}", config.get(Serializer.PROPERTY_COMPRESS_OUTPUT));
        }

        final String internalId = getConfigAttributeValue( serializer, Serializer.ADD_EXIST_ID_ATTRIBUTE );

        if( internalId != null ) {
            config.put( Serializer.PROPERTY_ADD_EXIST_ID, internalId );
            LOG.debug(Serializer.PROPERTY_ADD_EXIST_ID + ": {}", config.get(Serializer.PROPERTY_ADD_EXIST_ID));
        }

        final String tagElementMatches = getConfigAttributeValue( serializer, Serializer.TAG_MATCHING_ELEMENTS_ATTRIBUTE );

        if( tagElementMatches != null ) {
            config.put( Serializer.PROPERTY_TAG_MATCHING_ELEMENTS, tagElementMatches );
            LOG.debug(Serializer.PROPERTY_TAG_MATCHING_ELEMENTS + ": {}", config.get(Serializer.PROPERTY_TAG_MATCHING_ELEMENTS));
        }

        final String tagAttributeMatches = getConfigAttributeValue( serializer, Serializer.TAG_MATCHING_ATTRIBUTES_ATTRIBUTE );

        if( tagAttributeMatches != null ) {
            config.put( Serializer.PROPERTY_TAG_MATCHING_ATTRIBUTES, tagAttributeMatches );
            LOG.debug(Serializer.PROPERTY_TAG_MATCHING_ATTRIBUTES + ": {}", config.get(Serializer.PROPERTY_TAG_MATCHING_ATTRIBUTES));
        }

        final NodeList nlFilters = serializer.getElementsByTagName( CustomMatchListenerFactory.CONFIGURATION_ELEMENT );

        if( nlFilters != null ) {
            final List<String> filters = new ArrayList<>(nlFilters.getLength());

            for (int i = 0; i < nlFilters.getLength(); i++) {
                final Element filterElem = (Element) nlFilters.item(i);
                final String filterClass = filterElem.getAttribute(CustomMatchListenerFactory.CONFIGURATION_ATTR_CLASS);

                if (filterClass != null) {
                    filters.add(filterClass);
                    LOG.debug(CustomMatchListenerFactory.CONFIG_MATCH_LISTENERS + ": {}", filterClass);
                } else {
                    LOG.warn("Configuration element " + CustomMatchListenerFactory.CONFIGURATION_ELEMENT + " needs an attribute 'class'");
                }
            }
            config.put(CustomMatchListenerFactory.CONFIG_MATCH_LISTENERS, filters);
        }

        final NodeList backupFilters = serializer.getElementsByTagName( SystemExport.CONFIGURATION_ELEMENT );

        if( backupFilters != null ) {
            final List<String> filters = new ArrayList<>(backupFilters.getLength());

            for (int i = 0; i < backupFilters.getLength(); i++) {
                final Element filterElem = (Element) backupFilters.item(i);
                final String filterClass = filterElem.getAttribute(CustomMatchListenerFactory.CONFIGURATION_ATTR_CLASS);

                if (filterClass != null) {
                    filters.add(filterClass);
                    LOG.debug(CustomMatchListenerFactory.CONFIG_MATCH_LISTENERS + ": {}", filterClass);
                } else {
                    LOG.warn("Configuration element " + SystemExport.CONFIGURATION_ELEMENT + " needs an attribute 'class'");
                }
            }
            if (!filters.isEmpty()) config.put(SystemExport.CONFIG_FILTERS, filters);
        }
    }


    /**
     * Reads the scheduler configuration.
     *
     * @param  scheduler  DOCUMENT ME!
     */
    private void configureScheduler(final Element scheduler)
    {
        final NodeList nlJobs = scheduler.getElementsByTagName(JobConfig.CONFIGURATION_JOB_ELEMENT_NAME);

        if(nlJobs == null) {
            return;
        }

        final List<JobConfig> jobList = new ArrayList<>();

        for(int i = 0; i < nlJobs.getLength(); i++) {
            final Element job = (Element)nlJobs.item( i );

            //get the job type
            final String strJobType = getConfigAttributeValue(job, JobConfig.JOB_TYPE_ATTRIBUTE);

            final JobType jobType;
            if(strJobType == null) {
                jobType = JobType.USER; //default to user if unspecified
            } else {
                jobType = JobType.valueOf(strJobType.toUpperCase(Locale.ENGLISH));
            }

            final String jobName = getConfigAttributeValue(job, JobConfig.JOB_NAME_ATTRIBUTE);

            //get the job resource
            String jobResource = getConfigAttributeValue(job, JobConfig.JOB_CLASS_ATTRIBUTE);
            if(jobResource == null) {
                jobResource = getConfigAttributeValue(job, JobConfig.JOB_XQUERY_ATTRIBUTE);
            }

            //get the job schedule
            String jobSchedule = getConfigAttributeValue(job, JobConfig.JOB_CRON_TRIGGER_ATTRIBUTE);
            if(jobSchedule == null) {
                jobSchedule = getConfigAttributeValue(job, JobConfig.JOB_PERIOD_ATTRIBUTE);
            }

            final String jobUnschedule = getConfigAttributeValue(job, JobConfig.JOB_UNSCHEDULE_ON_EXCEPTION);
            
            //create the job config
            try {
                final JobConfig jobConfig = new JobConfig(jobType, jobName, jobResource, jobSchedule, jobUnschedule);

                //get and set the job delay
                final String jobDelay = getConfigAttributeValue(job, JobConfig.JOB_DELAY_ATTRIBUTE);

                if((jobDelay != null) && (!jobDelay.isEmpty())) {
                    jobConfig.setDelay(Long.parseLong(jobDelay));
                }

                //get and set the job repeat
                final String jobRepeat = getConfigAttributeValue(job, JobConfig.JOB_REPEAT_ATTRIBUTE);

                if((jobRepeat != null) && (!jobRepeat.isEmpty())) {
                    jobConfig.setRepeat(Integer.parseInt(jobRepeat));
                }

                final NodeList nlParam = job.getElementsByTagName(ParametersExtractor.PARAMETER_ELEMENT_NAME);
                final Map<String, List<? extends Object>> params = ParametersExtractor.extract(nlParam);
                
                for(final Entry<String, List<? extends Object>> param : params.entrySet()) {
                    final List<? extends Object> values = param.getValue();
                    if(values != null && !values.isEmpty()) {
                        jobConfig.addParameter(param.getKey(), values.get(0).toString());
                        
                        if(values.size() > 1) {
                            LOG.warn("Parameter '{}' for job '{}' has more than one value, ignoring further values.", param.getKey(), jobName);
                        }
                    }
                }

                jobList.add(jobConfig);

                LOG.debug("Configured scheduled '{}' job '{}{}{}{}'", jobType, jobResource, (jobSchedule == null) ? "" : ("' with trigger '" + jobSchedule), (jobDelay == null) ? "" : ("' with delay '" + jobDelay), (jobRepeat == null) ? "" : ("' repetitions '" + jobRepeat));
            } catch(final JobException je) {
                LOG.error(je);
            }
        }

        if(!jobList.isEmpty()) {
            final JobConfig[] configs = new JobConfig[jobList.size()];

            for(int i = 0; i < jobList.size(); i++) {
                configs[i] = (JobConfig)jobList.get(i);
            }

            config.put(JobConfig.PROPERTY_SCHEDULER_JOBS, configs);
        }
    }
    

    /**
     * DOCUMENT ME!
     *
     * @param   dbHome
     * @param   con
     *
     * @throws  DatabaseConfigurationException
     */
    private void configureBackend( final Optional<Path> dbHome, Element con ) throws DatabaseConfigurationException
    {
        final String database = getConfigAttributeValue(con, BrokerFactory.PROPERTY_DATABASE);
        if (database != null) {
            config.put(BrokerFactory.PROPERTY_DATABASE, database);
            LOG.debug(BrokerFactory.PROPERTY_DATABASE + ": {}", config.get(BrokerFactory.PROPERTY_DATABASE));
        }

        // directory for database files
        final String dataFiles = getConfigAttributeValue( con, BrokerPool.DATA_DIR_ATTRIBUTE );

        if (dataFiles != null) {
            final Path df = ConfigurationHelper.lookup( dataFiles, dbHome );
            if (!Files.isReadable(df)) {
                try {
                    Files.createDirectories(df);
                } catch (final IOException ioe) {
                    throw new DatabaseConfigurationException("cannot read data directory: " + df.toAbsolutePath().toString(), ioe);
                }
            }
            config.put(BrokerPool.PROPERTY_DATA_DIR, df.toAbsolutePath());
            LOG.debug(BrokerPool.PROPERTY_DATA_DIR + ": {}", config.get(BrokerPool.PROPERTY_DATA_DIR));
        }

        String cacheMem = getConfigAttributeValue( con, DefaultCacheManager.CACHE_SIZE_ATTRIBUTE );

        if( cacheMem != null ) {

            if( cacheMem.endsWith( "M" ) || cacheMem.endsWith( "m" ) ) {
                cacheMem = cacheMem.substring( 0, cacheMem.length() - 1 );
            }

            try {
                config.put( DefaultCacheManager.PROPERTY_CACHE_SIZE, Integer.valueOf(cacheMem) );
                LOG.debug(DefaultCacheManager.PROPERTY_CACHE_SIZE + ": {}m", config.get(DefaultCacheManager.PROPERTY_CACHE_SIZE));
            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + DefaultCacheManager.PROPERTY_CACHE_SIZE + " value to integer: {}", cacheMem, nfe);
            }
        }
        
        // Process the Check Max Cache value
        
        String checkMaxCache = getConfigAttributeValue( con, DefaultCacheManager.CACHE_CHECK_MAX_SIZE_ATTRIBUTE );
        
        if( checkMaxCache == null ) {
            checkMaxCache = DefaultCacheManager.DEFAULT_CACHE_CHECK_MAX_SIZE_STRING;
        }
        
        config.put( DefaultCacheManager.PROPERTY_CACHE_CHECK_MAX_SIZE, parseBoolean( checkMaxCache, true ) );
        LOG.debug(DefaultCacheManager.PROPERTY_CACHE_CHECK_MAX_SIZE + ": {}", config.get(DefaultCacheManager.PROPERTY_CACHE_CHECK_MAX_SIZE));

        String cacheShrinkThreshold = getConfigAttributeValue( con, DefaultCacheManager.SHRINK_THRESHOLD_ATTRIBUTE );

        if( cacheShrinkThreshold == null ) {
            cacheShrinkThreshold = DefaultCacheManager.DEFAULT_SHRINK_THRESHOLD_STRING;
        }

        try {
            config.put(DefaultCacheManager.SHRINK_THRESHOLD_PROPERTY, Integer.valueOf(cacheShrinkThreshold));
            LOG.debug(DefaultCacheManager.SHRINK_THRESHOLD_PROPERTY + ": {}", config.get(DefaultCacheManager.SHRINK_THRESHOLD_PROPERTY));
        } catch(final NumberFormatException nfe) {
            LOG.warn("Cannot convert " + DefaultCacheManager.SHRINK_THRESHOLD_PROPERTY + " value to integer: {}", cacheShrinkThreshold, nfe);
        }

        String collectionCache = getConfigAttributeValue(con, CollectionCache.CACHE_SIZE_ATTRIBUTE);
        if(collectionCache != null) {
            collectionCache = collectionCache.toLowerCase();

            try {
                final int collectionCacheBytes;
                if(collectionCache.endsWith("k")) {
                    collectionCacheBytes = 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 1));
                } else if(collectionCache.endsWith("kb")) {
                    collectionCacheBytes = 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 2));
                } else if(collectionCache.endsWith("m")) {
                    collectionCacheBytes = 1024 * 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 1));
                } else if(collectionCache.endsWith("mb")) {
                    collectionCacheBytes = 1024 * 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 2));
                } else if(collectionCache.endsWith("g")) {
                    collectionCacheBytes = 1024 * 1024 * 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 1));
                } else if(collectionCache.endsWith("gb")) {
                    collectionCacheBytes = 1024 * 1024 * 1024 * Integer.parseInt(collectionCache.substring(0, collectionCache.length() - 2));
                } else {
                    collectionCacheBytes = Integer.parseInt(collectionCache);
                }

                config.put(CollectionCache.PROPERTY_CACHE_SIZE_BYTES, collectionCacheBytes);

                if(LOG.isDebugEnabled()) {
                    LOG.debug("Set config {} = {}", CollectionCache.PROPERTY_CACHE_SIZE_BYTES, config.get(CollectionCache.PROPERTY_CACHE_SIZE_BYTES));
                }
            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + CollectionCache.PROPERTY_CACHE_SIZE_BYTES + " value to integer: {}", collectionCache, nfe);
            }
        }

        final String pageSize = getConfigAttributeValue( con, NativeBroker.PAGE_SIZE_ATTRIBUTE );

        if( pageSize != null ) {

            try {
                config.put( BrokerPool.PROPERTY_PAGE_SIZE, Integer.valueOf(pageSize) );
                LOG.debug(BrokerPool.PROPERTY_PAGE_SIZE + ": {}", config.get(BrokerPool.PROPERTY_PAGE_SIZE));
            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + BrokerPool.PROPERTY_PAGE_SIZE + " value to integer: {}", pageSize, nfe);
            }
        }

        //Not clear : rather looks like a buffers count
        final String collCacheSize = getConfigAttributeValue( con, BrokerPool.COLLECTION_CACHE_SIZE_ATTRIBUTE );

        if( collCacheSize != null ) {

            try {
                config.put( BrokerPool.PROPERTY_COLLECTION_CACHE_SIZE, Integer.valueOf(collCacheSize) );
                LOG.debug(BrokerPool.PROPERTY_COLLECTION_CACHE_SIZE + ": {}", config.get(BrokerPool.PROPERTY_COLLECTION_CACHE_SIZE));
            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + BrokerPool.PROPERTY_COLLECTION_CACHE_SIZE + " value to integer: {}", collCacheSize, nfe);
            }
        }

        final String nodesBuffer = getConfigAttributeValue( con, BrokerPool.NODES_BUFFER_ATTRIBUTE );

        if( nodesBuffer != null ) {

            try {
                config.put( BrokerPool.PROPERTY_NODES_BUFFER, Integer.valueOf(nodesBuffer) );
                LOG.debug(BrokerPool.PROPERTY_NODES_BUFFER + ": {}", config.get(BrokerPool.PROPERTY_NODES_BUFFER));

            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + BrokerPool.PROPERTY_NODES_BUFFER + " value to integer: {}", nodesBuffer, nfe);
            }
        }

        String diskSpace = getConfigAttributeValue(con, BrokerPool.DISK_SPACE_MIN_ATTRIBUTE);

        if( diskSpace != null ) {

            if( diskSpace.endsWith( "M" ) || diskSpace.endsWith( "m" ) ) {
                diskSpace = diskSpace.substring( 0, diskSpace.length() - 1 );
            }

            try {
                config.put(BrokerPool.DISK_SPACE_MIN_PROPERTY, Short.valueOf(diskSpace));
            }
            catch( final NumberFormatException nfe ) {
                LOG.warn("Cannot convert " + BrokerPool.DISK_SPACE_MIN_PROPERTY + " value to integer: {}", diskSpace, nfe);
            }
        }

        final String posixChownRestrictedStr = getConfigAttributeValue(con,  DBBroker.POSIX_CHOWN_RESTRICTED_ATTRIBUTE);
        final boolean posixChownRestricted;
        if(posixChownRestrictedStr == null) {
            posixChownRestricted = true;  // default
        } else {
            if(Boolean.parseBoolean(posixChownRestrictedStr)) {
                posixChownRestricted = true;
            } else {
                // configuration explicitly specifies that posix chown should NOT be restricted
                posixChownRestricted = false;
            }
        }
        config.put(DBBroker.POSIX_CHOWN_RESTRICTED_PROPERTY, posixChownRestricted);

        final String preserveOnCopyStr = getConfigAttributeValue(con,  DBBroker.PRESERVE_ON_COPY_ATTRIBUTE);
        final DBBroker.PreserveType preserveOnCopy;
        if(preserveOnCopyStr == null) {
            preserveOnCopy = DBBroker.PreserveType.NO_PRESERVE;  // default
        } else {
            if(Boolean.parseBoolean(preserveOnCopyStr)) {
                // configuration explicitly specifies that attributes should be preserved on copy
                preserveOnCopy = DBBroker.PreserveType.PRESERVE;
            } else {
                preserveOnCopy = DBBroker.PreserveType.NO_PRESERVE;
            }
        }
        config.put(DBBroker.PRESERVE_ON_COPY_PROPERTY, preserveOnCopy);

        final NodeList startupConf = con.getElementsByTagName(BrokerPool.CONFIGURATION_STARTUP_ELEMENT_NAME);
        if(startupConf.getLength() > 0) {
            configureStartup((Element)startupConf.item(0));
        } else {
            // Prevent NPE
            final List<StartupTriggerConfig> startupTriggers = new ArrayList<>();
            config.put(BrokerPool.PROPERTY_STARTUP_TRIGGERS, startupTriggers);
        }
        
        final NodeList poolConf = con.getElementsByTagName( BrokerPool.CONFIGURATION_POOL_ELEMENT_NAME );

        if( poolConf.getLength() > 0 ) {
            configurePool( (Element)poolConf.item( 0 ) );
        }

        final NodeList queryPoolConf = con.getElementsByTagName( XQueryPool.CONFIGURATION_ELEMENT_NAME );

        if( queryPoolConf.getLength() > 0 ) {
            configureXQueryPool( (Element)queryPoolConf.item( 0 ) );
        }

        final NodeList watchConf = con.getElementsByTagName( XQueryWatchDog.CONFIGURATION_ELEMENT_NAME );

        if( watchConf.getLength() > 0 ) {
            configureWatchdog( (Element)watchConf.item( 0 ) );
        }

        final NodeList recoveries = con.getElementsByTagName( BrokerPool.CONFIGURATION_RECOVERY_ELEMENT_NAME );

        if( recoveries.getLength() > 0 ) {
            configureRecovery( dbHome, (Element)recoveries.item( 0 ) );
        }
    }


    private void configureRecovery( final Optional<Path> dbHome, Element recovery ) throws DatabaseConfigurationException
    {
        String option = getConfigAttributeValue( recovery, BrokerPool.RECOVERY_ENABLED_ATTRIBUTE );
        setProperty( BrokerPool.PROPERTY_RECOVERY_ENABLED, parseBoolean( option, true ) );
        LOG.debug(BrokerPool.PROPERTY_RECOVERY_ENABLED + ": {}", config.get(BrokerPool.PROPERTY_RECOVERY_ENABLED));

        option = getConfigAttributeValue( recovery, Journal.RECOVERY_SYNC_ON_COMMIT_ATTRIBUTE );
        setProperty( Journal.PROPERTY_RECOVERY_SYNC_ON_COMMIT, parseBoolean( option, true ) );
        LOG.debug(Journal.PROPERTY_RECOVERY_SYNC_ON_COMMIT + ": {}", config.get(Journal.PROPERTY_RECOVERY_SYNC_ON_COMMIT));

        option = getConfigAttributeValue( recovery, BrokerPool.RECOVERY_GROUP_COMMIT_ATTRIBUTE );
        setProperty( BrokerPool.PROPERTY_RECOVERY_GROUP_COMMIT, parseBoolean( option, false ) );
        LOG.debug(BrokerPool.PROPERTY_RECOVERY_GROUP_COMMIT + ": {}", config.get(BrokerPool.PROPERTY_RECOVERY_GROUP_COMMIT));

        option = getConfigAttributeValue( recovery, Journal.RECOVERY_JOURNAL_DIR_ATTRIBUTE );

        if(option != null) {
            //DWES
            final Path rf = ConfigurationHelper.lookup( option, dbHome );

            if(!Files.isReadable(rf)) {
                throw new DatabaseConfigurationException( "cannot read data directory: " + rf.toAbsolutePath());
            }
            setProperty(Journal.PROPERTY_RECOVERY_JOURNAL_DIR, rf.toAbsolutePath());
            LOG.debug(Journal.PROPERTY_RECOVERY_JOURNAL_DIR + ": {}", config.get(Journal.PROPERTY_RECOVERY_JOURNAL_DIR));
        }

        option = getConfigAttributeValue( recovery, Journal.RECOVERY_SIZE_LIMIT_ATTRIBUTE );

        if( option != null ) {

            if( option.endsWith( "M" ) || option.endsWith( "m" ) ) {
                option = option.substring( 0, option.length() - 1 );
            }

            try {
                final Integer size = Integer.valueOf( option );
                setProperty( Journal.PROPERTY_RECOVERY_SIZE_LIMIT, size );
                LOG.debug(Journal.PROPERTY_RECOVERY_SIZE_LIMIT + ": {}m", config.get(Journal.PROPERTY_RECOVERY_SIZE_LIMIT));
            }
            catch( final NumberFormatException e ) {
                throw( new DatabaseConfigurationException( "size attribute in recovery section needs to be a number" ) );
            }
        }

        option = getConfigAttributeValue( recovery, BrokerPool.RECOVERY_FORCE_RESTART_ATTRIBUTE );
        boolean value = false;

        if( option != null ) {
            value = "yes".equals(option);
        }
        setProperty( BrokerPool.PROPERTY_RECOVERY_FORCE_RESTART, value);
        LOG.debug(BrokerPool.PROPERTY_RECOVERY_FORCE_RESTART + ": {}", config.get(BrokerPool.PROPERTY_RECOVERY_FORCE_RESTART));

        option = getConfigAttributeValue( recovery, BrokerPool.RECOVERY_POST_RECOVERY_CHECK );
        value  = false;

        if( option != null ) {
            value = "yes".equals(option);
        }
        setProperty( BrokerPool.PROPERTY_RECOVERY_CHECK, value);
        LOG.debug(BrokerPool.PROPERTY_RECOVERY_CHECK + ": {}", config.get(BrokerPool.PROPERTY_RECOVERY_CHECK));
    }

    /**
     * DOCUMENT ME!
     *
     * @param  watchDog
     */
    private void configureWatchdog( Element watchDog )
    {
        final String timeout = getConfigAttributeValue( watchDog, "query-timeout" );

        if( timeout != null ) {

            try {
                config.put( XQueryWatchDog.PROPERTY_QUERY_TIMEOUT, Long.valueOf(timeout) );
                LOG.debug(XQueryWatchDog.PROPERTY_QUERY_TIMEOUT + ": {}", config.get(XQueryWatchDog.PROPERTY_QUERY_TIMEOUT));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String maxOutput = getConfigAttributeValue( watchDog, "output-size-limit" );

        if( maxOutput != null ) {

            try {
                config.put( XQueryWatchDog.PROPERTY_OUTPUT_SIZE_LIMIT, Integer.valueOf(maxOutput) );
                LOG.debug(XQueryWatchDog.PROPERTY_OUTPUT_SIZE_LIMIT + ": {}", config.get(XQueryWatchDog.PROPERTY_OUTPUT_SIZE_LIMIT));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }
    }


    /**
     * DOCUMENT ME!
     *
     * @param  queryPool
     */
    private void configureXQueryPool( Element queryPool )
    {
        final String maxStackSize = getConfigAttributeValue( queryPool, XQueryPool.MAX_STACK_SIZE_ATTRIBUTE );

        if( maxStackSize != null ) {

            try {
                config.put( XQueryPool.PROPERTY_MAX_STACK_SIZE, Integer.valueOf(maxStackSize) );
                LOG.debug(XQueryPool.PROPERTY_MAX_STACK_SIZE + ": {}", config.get(XQueryPool.PROPERTY_MAX_STACK_SIZE));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String maxPoolSize = getConfigAttributeValue( queryPool, XQueryPool.POOL_SIZE_ATTTRIBUTE );

        if( maxPoolSize != null ) {

            try {
                config.put( XQueryPool.PROPERTY_POOL_SIZE, Integer.valueOf(maxPoolSize) );
                LOG.debug(XQueryPool.PROPERTY_POOL_SIZE + ": {}", config.get(XQueryPool.PROPERTY_POOL_SIZE));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }
    }
    
    public static class StartupTriggerConfig {
        private final String clazz;
        private final Map<String, List<? extends Object>> params;

        public StartupTriggerConfig(final String clazz, final Map<String, List<? extends Object>> params) {
            this.clazz = clazz;
            this.params = params;
        }

        public String getClazz() {
            return clazz;
        }

        public Map<String, List<? extends Object>> getParams() {
            return params;
        }
    }

    private void configureStartup(final Element startup) {
        // Retrieve <triggers>
        final NodeList nlTriggers = startup.getElementsByTagName("triggers");
        
        // If <triggers> exists
        if(nlTriggers != null && nlTriggers.getLength() > 0) {
            // Get <triggers>
            final Element triggers = (Element)nlTriggers.item(0);
            
            // Get <trigger>
            final NodeList nlTrigger = triggers.getElementsByTagName("trigger");
            
            // If <trigger> exists and there are more than 0
            if(nlTrigger != null && nlTrigger.getLength() > 0) {
                
                // Initialize trigger configuration
                List<StartupTriggerConfig> startupTriggers = (List<StartupTriggerConfig>)config.get(BrokerPool.PROPERTY_STARTUP_TRIGGERS);
                if(startupTriggers == null) {
                    startupTriggers = new ArrayList<>();
                    config.put(BrokerPool.PROPERTY_STARTUP_TRIGGERS, startupTriggers);
                }
                
                // Iterate over <trigger> elements
                for(int i = 0; i < nlTrigger.getLength(); i++) {
                    
                    // Get <trigger> element
                    final Element trigger = (Element)nlTrigger.item(i);
                    
                    // Get @class
                    final String startupTriggerClass = trigger.getAttribute("class");
                    
                    boolean isStartupTrigger = false;
                    try {
                        // Verify if class is StartupTrigger
                        for(final Class iface : Class.forName(startupTriggerClass).getInterfaces()) {
                            if("org.exist.storage.StartupTrigger".equals(iface.getName())) {
                                isStartupTrigger = true;
                                break;
                            }
                        }

                        // if it actually is a StartupTrigger
                        if(isStartupTrigger) {
                            // Parse additional parameters
                            final Map<String, List<? extends Object>> params 
                                    = ParametersExtractor.extract(trigger.getElementsByTagName(ParametersExtractor.PARAMETER_ELEMENT_NAME));
                            
                            // Register trigger
                            startupTriggers.add(new StartupTriggerConfig(startupTriggerClass, params));
                            
                            // Done
                            LOG.info("Registered StartupTrigger: {}", startupTriggerClass);
                            
                        } else {
                            LOG.warn("StartupTrigger: {} does not implement org.exist.storage.StartupTrigger. IGNORING!", startupTriggerClass);
                        }
                        
                    } catch(final ClassNotFoundException cnfe) {
                        LOG.error("Could not find StartupTrigger class: {}. {}", startupTriggerClass, cnfe.getMessage(), cnfe);
                    }
                }
            }
        }
    }
    

    /**
     * DOCUMENT ME!
     *
     * @param  pool
     */
    private void configurePool( Element pool )
    {
        final String min = getConfigAttributeValue( pool, BrokerPool.MIN_CONNECTIONS_ATTRIBUTE );

        if( min != null ) {

            try {
                config.put( BrokerPool.PROPERTY_MIN_CONNECTIONS, Integer.valueOf(min) );
                LOG.debug(BrokerPool.PROPERTY_MIN_CONNECTIONS + ": {}", config.get(BrokerPool.PROPERTY_MIN_CONNECTIONS));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String max = getConfigAttributeValue( pool, BrokerPool.MAX_CONNECTIONS_ATTRIBUTE );

        if( max != null ) {

            try {
                config.put( BrokerPool.PROPERTY_MAX_CONNECTIONS, Integer.valueOf(max) );
                LOG.debug(BrokerPool.PROPERTY_MAX_CONNECTIONS + ": {}", config.get(BrokerPool.PROPERTY_MAX_CONNECTIONS));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String sync = getConfigAttributeValue( pool, BrokerPool.SYNC_PERIOD_ATTRIBUTE );

        if( sync != null ) {

            try {
                config.put( BrokerPool.PROPERTY_SYNC_PERIOD, Long.valueOf(sync) );
                LOG.debug(BrokerPool.PROPERTY_SYNC_PERIOD + ": {}", config.get(BrokerPool.PROPERTY_SYNC_PERIOD));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String maxShutdownWait = getConfigAttributeValue( pool, BrokerPool.SHUTDOWN_DELAY_ATTRIBUTE );

        if( maxShutdownWait != null ) {

            try {
                config.put( BrokerPool.PROPERTY_SHUTDOWN_DELAY, Long.valueOf(maxShutdownWait) );
                LOG.debug(BrokerPool.PROPERTY_SHUTDOWN_DELAY + ": {}", config.get(BrokerPool.PROPERTY_SHUTDOWN_DELAY));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }
    }


    private void configureIndexer( final Optional<Path> dbHome, Document doc, Element indexer ) throws DatabaseConfigurationException, MalformedURLException
    {
        final String caseSensitive = getConfigAttributeValue( indexer, NativeValueIndex.INDEX_CASE_SENSITIVE_ATTRIBUTE );

        if( caseSensitive != null ) {
            config.put( NativeValueIndex.PROPERTY_INDEX_CASE_SENSITIVE, parseBoolean( caseSensitive, false ) );
            LOG.debug(NativeValueIndex.PROPERTY_INDEX_CASE_SENSITIVE + ": {}", config.get(NativeValueIndex.PROPERTY_INDEX_CASE_SENSITIVE));
        }

        int    depth      = 3;
        final String indexDepth = getConfigAttributeValue( indexer, NativeBroker.INDEX_DEPTH_ATTRIBUTE );

        if( indexDepth != null ) {

            try {
                depth = Integer.parseInt( indexDepth );

                if( depth < 3 ) {
                    LOG.warn( "parameter index-depth should be >= 3 or you will experience a severe " + "performance loss for node updates (XUpdate or XQuery update extensions)" );
                    depth = 3;
                }
                config.put( NativeBroker.PROPERTY_INDEX_DEPTH, depth);
                LOG.debug(NativeBroker.PROPERTY_INDEX_DEPTH + ": {}", config.get(NativeBroker.PROPERTY_INDEX_DEPTH));
            }
            catch( final NumberFormatException e ) {
                LOG.warn( e );
            }
        }

        final String suppressWS = getConfigAttributeValue( indexer, Indexer.SUPPRESS_WHITESPACE_ATTRIBUTE );

        if( suppressWS != null ) {
            config.put( Indexer.PROPERTY_SUPPRESS_WHITESPACE, suppressWS );
            LOG.debug(Indexer.PROPERTY_SUPPRESS_WHITESPACE + ": {}", config.get(Indexer.PROPERTY_SUPPRESS_WHITESPACE));
        }

        final String suppressWSmixed = getConfigAttributeValue( indexer, Indexer.PRESERVE_WS_MIXED_CONTENT_ATTRIBUTE );

        if( suppressWSmixed != null ) {
            config.put( Indexer.PROPERTY_PRESERVE_WS_MIXED_CONTENT, parseBoolean( suppressWSmixed, false ) );
            LOG.debug(Indexer.PROPERTY_PRESERVE_WS_MIXED_CONTENT + ": {}", config.get(Indexer.PROPERTY_PRESERVE_WS_MIXED_CONTENT));
        }

        // index settings
        final NodeList cl = doc.getElementsByTagName( Indexer.CONFIGURATION_INDEX_ELEMENT_NAME );

        if( cl.getLength() > 0 ) {
            final Element   elem = (Element)cl.item( 0 );
            final IndexSpec spec = new IndexSpec( null, elem );
            config.put( Indexer.PROPERTY_INDEXER_CONFIG, spec );
            //LOG.debug(Indexer.PROPERTY_INDEXER_CONFIG + ": " + config.get(Indexer.PROPERTY_INDEXER_CONFIG));
        }

        // index modules
        NodeList modules = indexer.getElementsByTagName( IndexManager.CONFIGURATION_ELEMENT_NAME );

        if( modules.getLength() > 0 ) {
            modules = ( (Element)modules.item( 0 ) ).getElementsByTagName( IndexManager.CONFIGURATION_MODULE_ELEMENT_NAME );
            final IndexModuleConfig[] modConfig = new IndexModuleConfig[modules.getLength()];

            for( int i = 0; i < modules.getLength(); i++ ) {
                final Element elem      = (Element)modules.item( i );
                final String  className = elem.getAttribute( IndexManager.INDEXER_MODULES_CLASS_ATTRIBUTE );
                final String  id        = elem.getAttribute( IndexManager.INDEXER_MODULES_ID_ATTRIBUTE );

                if( ( className == null ) || (className.isEmpty()) ) {
                    throw( new DatabaseConfigurationException( "Required attribute class is missing for module" ) );
                }

                if( ( id == null ) || (id.isEmpty()) ) {
                    throw( new DatabaseConfigurationException( "Required attribute id is missing for module" ) );
                }
                modConfig[i] = new IndexModuleConfig( id, className, elem );
            }
            config.put( IndexManager.PROPERTY_INDEXER_MODULES, modConfig );
        }
    }


    private void configureValidation(final Optional<Path> dbHome, final Element validation) throws DatabaseConfigurationException {
        // Determine validation mode
        final String mode = getConfigAttributeValue(validation, XMLReaderObjectFactory.VALIDATION_MODE_ATTRIBUTE);
        if (mode != null) {
            config.put(XMLReaderObjectFactory.PROPERTY_VALIDATION_MODE, mode);
            if (LOG.isDebugEnabled()) {
                LOG.debug(XMLReaderObjectFactory.PROPERTY_VALIDATION_MODE + ": {}", config.get(XMLReaderObjectFactory.PROPERTY_VALIDATION_MODE));
            }
        }

        // Configure the Entity Resolver
        final NodeList entityResolver = validation.getElementsByTagName(XMLReaderObjectFactory.CONFIGURATION_ENTITY_RESOLVER_ELEMENT_NAME);
        if (entityResolver.getLength() > 0) {
            LOG.info("Creating xmlresolver.org OASIS Catalog resolver");

            final Element elemEntityResolver = (Element) entityResolver.item(0);
            final NodeList nlCatalogs = elemEntityResolver.getElementsByTagName(XMLReaderObjectFactory.CONFIGURATION_CATALOG_ELEMENT_NAME);

            // Determine webapps directory. SingleInstanceConfiguration cannot
            // be used at this phase. Trick is to check whether dbHOME is
            // pointing to a WEB-INF directory, meaning inside the war file.
            final Path webappHome = dbHome.map(h -> {
                if(FileUtils.fileName(h).endsWith("WEB-INF")) {
                    return h.getParent().toAbsolutePath();
                } else {
                    return h.resolve("webapp").toAbsolutePath();
                }
            }).orElse(Paths.get("webapp").toAbsolutePath());

            if (LOG.isDebugEnabled()) {
                LOG.debug("Found " + nlCatalogs.getLength() + " catalog uri entries.");
                LOG.debug("Using dbHome=" + dbHome);
                LOG.debug("using webappHome=" + webappHome.toString());
            }

            // Get the Catalog URIs
            final List<String> catalogUris = new ArrayList<>();
            for (int i = 0; i < nlCatalogs.getLength(); i++) {
                String uri = ((Element) nlCatalogs.item(i)).getAttribute("uri");

                if (uri != null) {
                    // Substitute string, creating an uri from a local file
                    if (uri.indexOf("${WEBAPP_HOME}") != -1) {
                        uri = uri.replaceAll("\\$\\{WEBAPP_HOME\\}", webappHome.toUri().toString());
                    }
                    if (uri.indexOf("${EXIST_HOME}") != -1) {
                        uri = uri.replaceAll("\\$\\{EXIST_HOME\\}", dbHome.toString());
                    }

                    // Add uri to configuration
                    LOG.info("Adding Catalog URI: " + uri);
                    catalogUris.add(uri);
                }
            }

            // Store all configured URIs
            config.put(XMLReaderObjectFactory.CATALOG_URIS, catalogUris);

            // Create and Store the resolver
            try {
                final List<Tuple2<String, Optional<InputSource>>> catalogs = catalogUris.stream().map(catalogUri -> Tuple(catalogUri, Optional.<InputSource>empty())).collect(Collectors.toList());
                final Resolver resolver = ResolverFactory.newResolver(catalogs);
                config.put(XMLReaderObjectFactory.CATALOG_RESOLVER, resolver);
            } catch (final URISyntaxException e) {
                LOG.error("Unable to parse catalog uri: " + e.getMessage(), e);
            }
        }

        // cache
        final GrammarPool gp = new GrammarPool();
        config.put( XMLReaderObjectFactory.GRAMMAR_POOL, gp);
    }
    
     /**
     * Gets the value of a configuration attribute
     *
     * The value typically is specified in the conf.xml file, but can be overridden with using a System Property
     *
     * @param   element        The attribute's parent element
     * @param   attributeName  The name of the attribute
     *
     * @return  The value of the attribute
     */
    private String getConfigAttributeValue( Element element, String attributeName )
    {
    	String value = null;
    	
    	if( element != null && attributeName != null ) {
    		final String property = getAttributeSystemPropertyName( element, attributeName );
    		
    		value = System.getProperty( property );
    		
    		// If the value has not been overriden in a system property, then get it from the configuration
    		
    		if( value != null ) {
                LOG.warn("Configuration value overridden by system property: {}, with value: {}", property, value);
    		} else {
    			value = element.getAttribute( attributeName );
    		}
    	}
    	
    	return( value );
    }
    
    /**
     * Generates a suitable system property name from the given config attribute and parent element.
     *
     * values are of the form org.element.element.....attribute and follow the heirarchical structure of the conf.xml file. 
     * For example, the db-connection cacheSize property name would be org.exist.db-connection.cacheSize
     *
     * @param   element        The attribute's parent element
     * @param   attributeName  The name of the attribute
     *
     * @return  The generated system property name
     */
    private String getAttributeSystemPropertyName( Element element, String attributeName )
    {
    	final StringBuilder  	property 	= new StringBuilder( attributeName );
    	Node			parent		= element.getParentNode();
    	
    	property.insert( 0, "." );
    	property.insert( 0,  element.getLocalName() );
    	
    	while( parent != null && parent instanceof Element ) {
    		final String parentName = ((Element)parent).getLocalName();
    		
    		property.insert( 0, "." );
    		property.insert( 0, parentName );
    		
    		parent   = parent.getParentNode();
    	}
    	
    	property.insert( 0, "org." );
    	
    	return( property.toString() );
    }


    public Optional<Path> getConfigFilePath() {
        return configFilePath;
    }


    public Optional<Path> getExistHome() {
        return existHome;
    }


    public Object getProperty(final String name) {
        return config.get(name);
    }

    public <T> T getProperty(final String name, final T defaultValue) {
        return Optional.ofNullable((T)config.get(name)).orElse(defaultValue);
    }

    public boolean hasProperty(final String name) {
        return config.containsKey(name);
    }


    public void setProperty(final String name, final Object obj) {
        config.put(name, obj);
    }

    public void removeProperty(final String name) {
        config.remove(name);
    }

    /**
     * Takes the passed string and converts it to a non-null <code>Boolean</code> object. If value is null, the specified default value is used.
     * Otherwise, Boolean.TRUE is returned if and only if the passed string equals &quot;yes&quot; or &quot;true&quot;, ignoring case.
     *
     * @param   value         The string to parse
     * @param   defaultValue  The default if the string is null
     *
     * @return  The parsed <code>Boolean</code>
     */
    public static boolean parseBoolean(@Nullable final String value, final boolean defaultValue) {
        return Optional.ofNullable(value)
                .map(v -> v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true"))
                .orElse(defaultValue);
    }

    /**
     * Takes the passed string and converts it to a non-null <code>int</code> value. If value is null, the specified default value is used.
     * Otherwise, Boolean.TRUE is returned if and only if the passed string equals &quot;yes&quot; or &quot;true&quot;, ignoring case.
     *
     * @param   value         The string to parse
     * @param   defaultValue  The default if the string is null or empty
     *
     * @return  The parsed <code>int</code>
     */
    public static int parseInt(@Nullable final String value, final int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            LOG.warn("Could not parse: {}, as an int: {}", value, e.getMessage());
            return defaultValue;
        }
    }

    public int getInteger(final String name) {
        return Optional.ofNullable(getProperty(name))
                .filter(v -> v instanceof Integer)
                .map(v -> (int)v)
                .orElse(-1);
    }


    /**
     * (non-Javadoc).
     *
     * @param   exception  DOCUMENT ME!
     *
     * @throws  SAXException  DOCUMENT ME!
     *
     * @see     org.xml.sax.ErrorHandler#error(org.xml.sax.SAXParseException)
     */
    @Override
    public void error( SAXParseException exception ) throws SAXException
    {
        LOG.error("error occurred while reading configuration file [line: {}]:{}", exception.getLineNumber(), exception.getMessage(), exception);
    }


    /**
     * (non-Javadoc).
     *
     * @param   exception  DOCUMENT ME!
     *
     * @throws  SAXException  DOCUMENT ME!
     *
     * @see     org.xml.sax.ErrorHandler#fatalError(org.xml.sax.SAXParseException)
     */
    @Override
    public void fatalError( SAXParseException exception ) throws SAXException
    {
        LOG.error("error occurred while reading configuration file [line: {}]:{}", exception.getLineNumber(), exception.getMessage(), exception);
    }


    /**
     * (non-Javadoc).
     *
     * @param   exception  DOCUMENT ME!
     *
     * @throws  SAXException  DOCUMENT ME!
     *
     * @see     org.xml.sax.ErrorHandler#warning(org.xml.sax.SAXParseException)
     */
    @Override
    public void warning( SAXParseException exception ) throws SAXException
    {
        LOG.error("error occurred while reading configuration file [line: {}]:{}", exception.getLineNumber(), exception.getMessage(), exception);
    }
    

    public static final class IndexModuleConfig {
        private final String id;
        private final String className;
        private final Element config;

        public IndexModuleConfig(final String id, final String className, final Element config) {
            this.id = id;
            this.className = className;
            this.config = config;
        }

        public String getId()
        {
            return( id );
        }

        public String getClassName()
        {
            return( className );
        }

        public Element getConfig()
        {
            return( config );
        }
    }

}
