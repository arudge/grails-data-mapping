/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.grails.datastore.mapping.mongo;

import static org.grails.datastore.mapping.config.utils.ConfigUtils.read;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.mongodb.*;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.grails.datastore.mapping.core.AbstractDatastore;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.core.StatelessDatastore;
import org.grails.datastore.mapping.document.config.DocumentMappingContext;
import org.grails.datastore.mapping.model.*;
import org.grails.datastore.mapping.mongo.config.MongoAttribute;
import org.grails.datastore.mapping.mongo.config.MongoCollection;
import org.grails.datastore.mapping.mongo.config.MongoMappingContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.authentication.UserCredentials;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.*;

/**
 * A Datastore implementation for the Mongo document store.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class MongoDatastore extends AbstractDatastore implements InitializingBean, MappingContext.Listener, DisposableBean, StatelessDatastore {

    public static final String PASSWORD = "password";
    public static final String USERNAME = "username";
    public static final String MONGO_PORT = "port";
    public static final String MONGO_HOST = "host";
    public static final String MONGO_STATELESS = "stateless";
    public static final String INDEX_ATTRIBUTES = "indexAttributes";

    protected Mongo mongo;
    protected MongoClientOptions mongoOptions;
    protected Map<PersistentEntity, MongoTemplate> mongoTemplates = new ConcurrentHashMap<PersistentEntity, MongoTemplate>();
    protected Map<PersistentEntity, String> mongoCollections = new ConcurrentHashMap<PersistentEntity, String>();
    protected boolean stateless = false;
    protected UserCredentials userCrentials;

    /**
     * Constructs a MongoDatastore using the default database name of "test" and defaults for the host and port.
     * Typically used during testing.
     */
    public MongoDatastore() {
        this(new MongoMappingContext("test"), Collections.<String, String>emptyMap(), null);
    }

    /**
     * Constructs a MongoDatastore using the given MappingContext and connection details map.
     *
     * @param mappingContext The MongoMappingContext
     * @param connectionDetails The connection details containing the {@link #MONGO_HOST} and {@link #MONGO_PORT} settings
     */
    public MongoDatastore(MongoMappingContext mappingContext,
            Map<String, String> connectionDetails, MongoClientOptions mongoOptions, ConfigurableApplicationContext ctx) {

        this(mappingContext, connectionDetails, ctx);
        if (mongoOptions != null) {
            this.mongoOptions = mongoOptions;
        }
    }

    /**
     * Constructs a MongoDatastore using the given MappingContext and connection details map.
     *
     * @param mappingContext The MongoMappingContext
     * @param connectionDetails The connection details containing the {@link #MONGO_HOST} and {@link #MONGO_PORT} settings
     */
    public MongoDatastore(MongoMappingContext mappingContext,
            Map<String, String> connectionDetails, ConfigurableApplicationContext ctx) {
        super(mappingContext, connectionDetails, ctx);

        if (mappingContext != null) {
            mappingContext.addMappingContextListener(this);
        }

        initializeConverters(mappingContext);

        mappingContext.getConverterRegistry().addConverter(new Converter<String, ObjectId>() {
            public ObjectId convert(String source) {
                return new ObjectId(source);
            }
        });

        mappingContext.getConverterRegistry().addConverter(new Converter<ObjectId, String>() {
            public String convert(ObjectId source) {
                return source.toString();
            }
        });

        mappingContext.getConverterRegistry().addConverter(new Converter<byte[], Binary>() {
            public Binary convert(byte[] source) {
                return new Binary(source);
            }
        });

        mappingContext.getConverterRegistry().addConverter(new Converter<Binary,byte[] >() {
            public byte[] convert(Binary source) {
                return source.getData();
            }
        });
    }

    public MongoDatastore(MongoMappingContext mappingContext) {
        this(mappingContext, Collections.<String, String>emptyMap(), null);
    }

    /**
     * Constructor for creating a MongoDatastore using an existing Mongo instance
     * @param mappingContext The MappingContext
     * @param mongo The existing Mongo instance
     */
    public MongoDatastore(MongoMappingContext mappingContext, Mongo mongo,
              ConfigurableApplicationContext ctx) {
        this(mappingContext, Collections.<String, String>emptyMap(), ctx);
        this.mongo = mongo;
    }

    /**
     * Constructor for creating a MongoDatastore using an existing Mongo instance. In this case
     * the connection details are only used to supply a USERNAME and PASSWORD
     *
     * @param mappingContext The MappingContext
     * @param mongo The existing Mongo instance
     */
    public MongoDatastore(MongoMappingContext mappingContext, Mongo mongo,
           Map<String, String> connectionDetails, ConfigurableApplicationContext ctx) {
        this(mappingContext, connectionDetails, ctx);
        this.mongo = mongo;
    }

    public Mongo getMongo() {
        return mongo;
    }

    public MongoTemplate getMongoTemplate(PersistentEntity entity) {
        return mongoTemplates.get(entity);
    }

    public String getCollectionName(PersistentEntity entity) {
        return mongoCollections.get(entity);
    }

    public UserCredentials getUserCrentials() {
        return userCrentials;
    }

    @Override
    protected Session createSession(Map<String, String> connDetails) {
        if(stateless) {
            return createStatelessSession(connectionDetails);
        }
        else {
            return new MongoSession(this, getMappingContext(), getApplicationEventPublisher(), false);
        }
    }

    @Override
    protected Session createStatelessSession(Map<String, String> connectionDetails) {
        return new MongoSession(this, getMappingContext(), getApplicationEventPublisher(), true);
    }

    public void afterPropertiesSet() throws Exception {
        if (mongo == null) {
            ServerAddress defaults = new ServerAddress();
            String username = read(String.class, USERNAME, connectionDetails, null);
            String password = read(String.class, PASSWORD, connectionDetails, null);
            DocumentMappingContext dc = (DocumentMappingContext) getMappingContext();
            String databaseName = dc.getDefaultDatabaseName();

            List<MongoCredential> credentials = new ArrayList<MongoCredential>();
            if(username != null && password != null) {
                credentials.add(MongoCredential.createCredential(username,databaseName, password.toCharArray() ));
            }
            ServerAddress serverAddress = new ServerAddress(  read(String.class, MONGO_HOST, connectionDetails, defaults.getHost()),
                                read(Integer.class, MONGO_PORT, connectionDetails, defaults.getPort())
            );
            this.stateless = read(Boolean.class, MONGO_STATELESS, connectionDetails, false);
            if(mongoOptions != null) {
                mongo = new MongoClient(serverAddress, credentials, mongoOptions);
            }
            else {
                mongo = new MongoClient(serverAddress, credentials);
            }
        }

        for (PersistentEntity entity : mappingContext.getPersistentEntities()) {
            // Only create Mongo templates for entities that are mapped with Mongo
            if (!entity.isExternal()) {
                createMongoTemplate(entity, mongo);
            }
        }
    }

    protected void createMongoTemplate(PersistentEntity entity, final Mongo mongoInstance) {
        DocumentMappingContext dc = (DocumentMappingContext) getMappingContext();
        String collectionName = entity.getDecapitalizedName();
        String databaseName = dc.getDefaultDatabaseName();
        @SuppressWarnings("unchecked") ClassMapping<MongoCollection> mapping = entity.getMapping();
        final MongoCollection mongoCollection = mapping.getMappedForm() != null ? mapping.getMappedForm() : null;

        if (mongoCollection != null) {
            if (mongoCollection.getCollection() != null) {
                collectionName = mongoCollection.getCollection();
            }
            if (mongoCollection.getDatabase() != null) {
                databaseName = mongoCollection.getDatabase();
            }
        }


        final String finalDatabaseName = databaseName;
        final MongoExceptionTranslator mongoExceptionTranslator = new MongoExceptionTranslator();
        final MongoTemplate mt = new MongoTemplate(new MongoDbFactory() {
            @Override
            public DB getDb() throws DataAccessException {
                return mongoInstance.getDB(finalDatabaseName);
            }

            @Override
            public DB getDb(String dbName) throws DataAccessException {
                return mongoInstance.getDB(dbName);
            }

            @Override
            public PersistenceExceptionTranslator getExceptionTranslator() {
                return mongoExceptionTranslator;
            }
        });

        if (mongoCollection != null) {
            final WriteConcern writeConcern = mongoCollection.getWriteConcern();
            if (writeConcern != null) {
                final String collectionNameToUse = collectionName;
                mt.executeInSession(new DbCallback<Object>() {
                    public Object doInDB(DB db) throws MongoException, DataAccessException {
                        DBCollection collection = db.getCollection(collectionNameToUse);
                        collection.setWriteConcern(writeConcern);
                        return null;
                    }
                });
            }
        }

        mongoTemplates.put(entity, mt);
        mongoCollections.put(entity, collectionName);

        initializeIndices(entity, mt);
    }

    /**
     * Indexes any properties that are mapped with index:true
     * @param entity The entity
     * @param template The template
     */
    protected void initializeIndices(final PersistentEntity entity, final MongoTemplate template) {
        template.execute(new DbCallback<Object>() {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            public Object doInDB(DB db) throws MongoException, DataAccessException {
                final DBCollection collection = db.getCollection(getCollectionName(entity));


                final ClassMapping<MongoCollection> classMapping = entity.getMapping();
                if (classMapping != null) {
                    final MongoCollection mappedForm = classMapping.getMappedForm();
                    if (mappedForm != null) {
                        List<MongoCollection.Index> indices = mappedForm.getIndices();
                        for (MongoCollection.Index index : indices) {
                            collection.createIndex(new BasicDBObject(index.getDefinition()), new BasicDBObject(index.getOptions()));
                        }

                        for (Map compoundIndex : mappedForm.getCompoundIndices()) {

                            Map indexAttributes = null;
                            if(compoundIndex.containsKey(INDEX_ATTRIBUTES)) {
                                Object o = compoundIndex.remove(INDEX_ATTRIBUTES);
                                if(o instanceof Map) {
                                    indexAttributes = (Map) o;
                                }
                            }
                            DBObject indexDef = new BasicDBObject(compoundIndex);
                            if(indexAttributes != null) {
                                collection.createIndex(indexDef, new BasicDBObject(indexAttributes));
                            }
                            else {
                                collection.createIndex(indexDef);
                            }
                        }
                    }
                }

                for (PersistentProperty<MongoAttribute> property : entity.getPersistentProperties()) {
                    final boolean indexed = isIndexed(property);

                    if (indexed) {
                        final MongoAttribute mongoAttributeMapping = property.getMapping().getMappedForm();
                        DBObject dbObject = new BasicDBObject();
                        final String fieldName = getMongoFieldNameForProperty(property);
                        dbObject.put(fieldName,1);
                        DBObject options = new BasicDBObject();
                        if (mongoAttributeMapping != null) {
                            Map attributes = mongoAttributeMapping.getIndexAttributes();
                            if (attributes != null) {
                                attributes = new HashMap(attributes);
                                if (attributes.containsKey(MongoAttribute.INDEX_TYPE)) {
                                    dbObject.put(fieldName, attributes.remove(MongoAttribute.INDEX_TYPE));
                                }
                                options.putAll(attributes);
                            }
                        }
                        // continue using deprecated method to support older versions of MongoDB
                        if (options.toMap().isEmpty()) {
                            collection.createIndex(dbObject);
                        }
                        else {
                            collection.createIndex(dbObject, options);
                        }
                    }
                }

                return null;
            }

            String getMongoFieldNameForProperty(PersistentProperty<MongoAttribute> property) {
                PropertyMapping<MongoAttribute> pm = property.getMapping();
                String propKey = null;
                if (pm.getMappedForm() != null) {
                    propKey = pm.getMappedForm().getField();
                }
                if (propKey == null) {
                    propKey = property.getName();
                }
                return propKey;
            }
        });
    }

    public void persistentEntityAdded(PersistentEntity entity) {
        createMongoTemplate(entity, mongo);
    }

    public void destroy() throws Exception {
        super.destroy();
        if (mongo != null) {
            mongo.close();
        }
    }

    @Override
    public boolean isSchemaless() {
        return true;
    }


}
