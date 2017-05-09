/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graph.internal;

import ai.grakn.Grakn;
import ai.grakn.GraknGraph;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Concept;
import ai.grakn.concept.ConceptId;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Instance;
import ai.grakn.concept.Relation;
import ai.grakn.concept.RelationType;
import ai.grakn.concept.Resource;
import ai.grakn.concept.ResourceType;
import ai.grakn.concept.RoleType;
import ai.grakn.concept.RuleType;
import ai.grakn.concept.Type;
import ai.grakn.concept.TypeLabel;
import ai.grakn.exception.ConceptException;
import ai.grakn.exception.ConceptNotUniqueException;
import ai.grakn.exception.GraknValidationException;
import ai.grakn.exception.GraphRuntimeException;
import ai.grakn.exception.InvalidConceptValueException;
import ai.grakn.exception.MoreThanOneConceptException;
import ai.grakn.factory.SystemKeyspace;
import ai.grakn.graph.admin.GraknAdmin;
import ai.grakn.graph.internal.computer.GraknSparkComputer;
import ai.grakn.graql.QueryBuilder;
import ai.grakn.graql.internal.query.QueryBuilderImpl;
import ai.grakn.util.EngineCommunicator;
import ai.grakn.util.ErrorMessage;
import ai.grakn.util.REST;
import ai.grakn.util.Schema;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static ai.grakn.graph.internal.RelationImpl.generateNewHash;
import static java.util.stream.Collectors.toSet;

/**
 * <p>
 *    The Grakn Graph Base Implementation
 * </p>
 *
 * <p>
 *     This defines how a grakn graph sits on top of a Tinkerpop {@link Graph}.
 *     It mostly act as a construction object which ensure the resulting graph conforms to the Grakn Object model.
 * </p>
 *
 * @author fppt
 *
 * @param <G> A vendor specific implementation of a Tinkerpop {@link Graph}.
 */
public abstract class AbstractGraknGraph<G extends Graph> implements GraknGraph, GraknAdmin {
    protected final Logger LOG = LoggerFactory.getLogger(AbstractGraknGraph.class);

    //TODO: Is this the correct place for these config paths
    //----------------------------- Config Paths
    public static final String SHARDING_THRESHOLD = "graph.sharding-threshold";
    public static final String NORMAL_CACHE_TIMEOUT_MS = "graph.ontology-cache-timeout-ms";
    public static final String BATCH_CACHE_TIMEOUT_MS = "graph.batch.ontology-cache-timeout-ms";

    //----------------------------- Graph Shared Variable
    private final String keyspace;
    private final String engine;
    private final Properties properties;
    private final boolean batchLoadingEnabled;
    private final G graph;
    private final ElementFactory elementFactory;
    private final long shardingFactor;
    private final Cache<TypeLabel, Type> cachedOntology;

    //----------------------------- Transaction Thread Bound
    private final ThreadLocal<ConceptLog> localConceptLog = new ThreadLocal<>();
    private final ThreadLocal<Boolean> localShowImplicitStructures = new ThreadLocal<>();
    private final ThreadLocal<Boolean> localIsOpen = new ThreadLocal<>();
    private final ThreadLocal<Boolean> localIsReadOnly = new ThreadLocal<>();
    private final ThreadLocal<String> localClosedReason = new ThreadLocal<>();
    private final ThreadLocal<Map<TypeLabel, Type>> localCloneCache = new ThreadLocal<>();

    public AbstractGraknGraph(G graph, String keyspace, String engine, boolean batchLoadingEnabled, Properties properties) {
        this.graph = graph;
        this.keyspace = keyspace;
        this.engine = engine;
        this.properties = properties;
        shardingFactor = Long.parseLong(properties.get(SHARDING_THRESHOLD).toString());

        elementFactory = new ElementFactory(this);

        localIsOpen.set(true);

        int cacheTimeout = Integer.parseInt(
                properties.get(batchLoadingEnabled ? BATCH_CACHE_TIMEOUT_MS : NORMAL_CACHE_TIMEOUT_MS).toString());
        cachedOntology = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(cacheTimeout, TimeUnit.MILLISECONDS)
                .build();

        if(initialiseMetaConcepts()) commitTransactionInternal();

        this.batchLoadingEnabled = batchLoadingEnabled;
        localShowImplicitStructures.set(false);
    }

    @Override
    public Optional<Integer> getId(TypeLabel label){
        if(getConceptLog().isTypeCached(label)){
            return Optional.of(getConceptLog().getCachedType(label).getTypeId());
        }
        return Optional.empty();
    }

    /**
     * Gets and increments the current available type id.
     *
     * @return the current available Grakn id which can be used for types
     */
    private int getNextTypeId(){
        //Instance count is used here to prevent creating another schema property.
        TypeImpl<?, ?> metaConcept = (TypeImpl<?, ?>) getMetaConcept();
        Integer currentValue = metaConcept.getProperty(Schema.ConceptProperty.INSTANCE_COUNT);
        if(currentValue == null){
            currentValue = Schema.MetaSchema.values().length + 1;
        } else {
            currentValue = currentValue + 1;
        }
        //Vertex is used directly here to bypass meta type mutation check
        metaConcept.getVertex().property(Schema.ConceptProperty.INSTANCE_COUNT.name(), currentValue);
        return currentValue;
    }

    /**
     * @param concept A concept in the graph
     * @return True if the concept has been modified in the transaction
     */
    public abstract boolean isConceptModified(ConceptImpl concept);

    /**
     *
     * @return The number of open transactions currently.
     */
    public abstract int numOpenTx();

    /**
     * Opens the thread bound transaction
     */
    public void openTransaction(GraknTxType txType){
        localIsOpen.set(true);
        if(GraknTxType.READ.equals(txType)) {
            localIsReadOnly.set(true);
        } else {
            localIsReadOnly.set(false);
        }
    }

    String getEngineUrl(){
        return engine;
    }

    Properties getProperties(){
        return properties;
    }

    @Override
    public String getKeyspace(){
        return keyspace;
    }

    Cache<TypeLabel, Type> getCachedOntology(){
        return cachedOntology;
    }

    ConceptLog getConceptLog() {
        return getObjectFromThreadLocal(localConceptLog, () -> {
            ConceptLog conceptLog = new ConceptLog(this);
            loadOntologyCacheIntoTransactionCache(conceptLog);
            return conceptLog;
        });
    }

    private Map<TypeLabel, Type> getCloneCache(){
        return getObjectFromThreadLocal(localCloneCache, HashMap::new);
    }

    private <X> X getObjectFromThreadLocal(ThreadLocal<X> threadLocal, Supplier<X> supplier){
        X object = threadLocal.get();
        if(object == null){
            threadLocal.set(object = supplier.get());
        }
        return object;
    }

    @Override
    public boolean isClosed(){
        return !getObjectFromThreadLocal(localIsOpen, () -> false);
    }
    public abstract boolean isSessionClosed();

    @Override
    public boolean implicitConceptsVisible(){
        return getObjectFromThreadLocal(localShowImplicitStructures, () -> false);
    }

    @Override
    public boolean isReadOnly(){
        return getObjectFromThreadLocal(localIsReadOnly, () -> false);
    }

    @Override
    public void showImplicitConcepts(boolean flag){
        localShowImplicitStructures.set(flag);
    }

    @Override
    public GraknAdmin admin(){
        return this;
    }

    @Override
    public <T extends Concept> T buildConcept(Vertex vertex) {
        return getElementFactory().buildConcept(vertex);
    }

    @Override
    public boolean isBatchLoadingEnabled(){
        return batchLoadingEnabled;
    }

    @SuppressWarnings("unchecked")
    private boolean initialiseMetaConcepts(){
        if(isMetaOntologyNotInitialised()){
            Vertex type = addTypeVertex(Schema.MetaSchema.CONCEPT.getId(), Schema.MetaSchema.CONCEPT.getLabel(), Schema.BaseType.TYPE);
            Vertex entityType = addTypeVertex(Schema.MetaSchema.ENTITY.getId(), Schema.MetaSchema.ENTITY.getLabel(), Schema.BaseType.ENTITY_TYPE);
            Vertex relationType = addTypeVertex(Schema.MetaSchema.RELATION.getId(), Schema.MetaSchema.RELATION.getLabel(), Schema.BaseType.RELATION_TYPE);
            Vertex resourceType = addTypeVertex(Schema.MetaSchema.RESOURCE.getId(), Schema.MetaSchema.RESOURCE.getLabel(), Schema.BaseType.RESOURCE_TYPE);
            Vertex roleType = addTypeVertex(Schema.MetaSchema.ROLE.getId(), Schema.MetaSchema.ROLE.getLabel(), Schema.BaseType.ROLE_TYPE);
            Vertex ruleType = addTypeVertex(Schema.MetaSchema.RULE.getId(), Schema.MetaSchema.RULE.getLabel(), Schema.BaseType.RULE_TYPE);
            Vertex inferenceRuleType = addTypeVertex(Schema.MetaSchema.INFERENCE_RULE.getId(), Schema.MetaSchema.INFERENCE_RULE.getLabel(), Schema.BaseType.RULE_TYPE);
            Vertex constraintRuleType = addTypeVertex(Schema.MetaSchema.CONSTRAINT_RULE.getId(), Schema.MetaSchema.CONSTRAINT_RULE.getLabel(), Schema.BaseType.RULE_TYPE);

            relationType.property(Schema.ConceptProperty.IS_ABSTRACT.name(), true);
            roleType.property(Schema.ConceptProperty.IS_ABSTRACT.name(), true);
            resourceType.property(Schema.ConceptProperty.IS_ABSTRACT.name(), true);
            ruleType.property(Schema.ConceptProperty.IS_ABSTRACT.name(), true);
            entityType.property(Schema.ConceptProperty.IS_ABSTRACT.name(), true);

            relationType.addEdge(Schema.EdgeLabel.SUB.getLabel(), type);
            roleType.addEdge(Schema.EdgeLabel.SUB.getLabel(), type);
            resourceType.addEdge(Schema.EdgeLabel.SUB.getLabel(), type);
            ruleType.addEdge(Schema.EdgeLabel.SUB.getLabel(), type);
            entityType.addEdge(Schema.EdgeLabel.SUB.getLabel(), type);
            inferenceRuleType.addEdge(Schema.EdgeLabel.SUB.getLabel(), ruleType);
            constraintRuleType.addEdge(Schema.EdgeLabel.SUB.getLabel(), ruleType);

            //Manual creation of shards on meta types which have instances
            createMetaShard(inferenceRuleType, Schema.BaseType.RULE_TYPE);
            createMetaShard(constraintRuleType, Schema.BaseType.RULE_TYPE);

            //Add Meta Ontology To Cache
            for (Schema.MetaSchema metaSchema : Schema.MetaSchema.values()) {
                getConceptLog().cacheConcept(getType(metaSchema.getId()));
            }

            return true;
        }

        return false;
    }
    private void createMetaShard(Vertex metaNode, Schema.BaseType baseType){
        Vertex metaShard = addVertex(baseType);
        metaShard.addEdge(Schema.EdgeLabel.SHARD.getLabel(), metaNode);
        metaShard.property(Schema.ConceptProperty.IS_SHARD.name(), true);
        metaNode.property(Schema.ConceptProperty.CURRENT_SHARD.name(), metaShard.id().toString());
    }

    private boolean isMetaOntologyNotInitialised(){
        return getMetaConcept() == null;
    }

    public G getTinkerPopGraph(){
        return graph;
    }

    @Override
    public GraphTraversal<Vertex, Vertex> getTinkerTraversal(){
        operateOnOpenGraph(() -> null); //This is to check if the graph is open
        ReadOnlyStrategy readOnlyStrategy = ReadOnlyStrategy.instance();
        return getTinkerPopGraph().traversal().asBuilder().with(readOnlyStrategy).create(getTinkerPopGraph()).V();
    }

    @Override
    public QueryBuilder graql(){
        return new QueryBuilderImpl(this);
    }

    ElementFactory getElementFactory(){
        return elementFactory;
    }

    //----------------------------------------------General Functionality-----------------------------------------------
    private EdgeImpl addEdge(Concept from, Concept to, Schema.EdgeLabel type){
        return ((ConceptImpl)from).addEdge((ConceptImpl) to, type);
    }

    @Override
    public <T extends Concept> T  getConcept(Schema.ConceptProperty key, Object value) {
        return getConcept(key, value, isBatchLoadingEnabled());
    }
    private  <T extends Concept> T  getConcept(Schema.ConceptProperty key, Object value, Boolean byPassDuplicates) {
        Iterator<Vertex> vertices = getTinkerTraversal().has(key.name(), value);

        if(vertices.hasNext()){
            Vertex vertex = vertices.next();
            if(!byPassDuplicates && vertices.hasNext()) {
                throw new MoreThanOneConceptException(ErrorMessage.TOO_MANY_CONCEPTS.getMessage(key.name(), value));
            }
            return getElementFactory().buildConcept(vertex);
        } else {
            return null;
        }
    }

    private Set<ConceptImpl> getConcepts(Schema.ConceptProperty key, Object value){
        Set<ConceptImpl> concepts = new HashSet<>();
        getTinkerTraversal().has(key.name(), value).
            forEachRemaining(v -> concepts.add(getElementFactory().buildConcept(v)));
        return concepts;
    }

    /**
     * Given a concept log bound to a specific thread the central ontology cache is read into this concept log.
     * This method performs this operation whilst making a deep clone of the cached concepts to ensure transactions
     * do not accidentally break the central ontology cache.
     *
     * @param conceptLog The thread bound concept log to read the snapshot into.
     */
    private void loadOntologyCacheIntoTransactionCache(ConceptLog conceptLog){
        ImmutableMap<TypeLabel, Type> cachedOntologySnapshot = ImmutableMap.copyOf(getCachedOntology().asMap());

        //Read central cache into conceptLog cloning only base concepts. Sets clones later
        for (Type type : cachedOntologySnapshot.values()) {
            conceptLog.cacheConcept((TypeImpl) clone(type));
        }

        //Iterate through cached clones completing the cloning process.
        //This part has to be done in a separate iteration otherwise we will infinitely recurse trying to clone everything
        for (Type type : ImmutableSet.copyOf(getCloneCache().values())) {
            //noinspection unchecked
            ((TypeImpl) type).copyCachedConcepts(cachedOntologySnapshot.get(type.getLabel()));
        }

        //Purge clone cache to save memory
        localCloneCache.remove();
    }

    /**
     *
     * @param type The type to clone
     * @param <X> The type of the concept
     * @return The newly deep cloned set
     */
    @SuppressWarnings({"unchecked", "ConstantConditions"})
    <X extends Type> X clone(X type){
        //Is a cloning even needed?
        if(getCloneCache().containsKey(type.getLabel())){
            return (X) getCloneCache().get(type.getLabel());
        }

        //If the clone has not happened then make a new one
        Type clonedType = type.copy();

        //Update clone cache so we don't clone multiple concepts in the same transaction
        getCloneCache().put(clonedType.getLabel(), clonedType);

        return (X) clonedType;
    }

    /**
     *
     * @param types a set of concepts to clone
     * @param <X> the type of those concepts
     * @return the set of concepts deep cloned
     */
    <X extends Type> Set<X> clone(Set<X> types){
        return types.stream().map(this::clone).collect(toSet());
    }

    void checkOntologyMutation(){
        checkMutation();
        //if(isBatchLoadingEnabled()){
        //    throw new GraphRuntimeException(ErrorMessage.SCHEMA_LOCKED.getMessage());
        //}
    }

    void checkMutation(){
        if(isReadOnly()) throw new GraphRuntimeException(ErrorMessage.TRANSACTION_READ_ONLY.getMessage(getKeyspace()));
    }


    //----------------------------------------------Concept Functionality-----------------------------------------------
    //------------------------------------ Construction
    Vertex addVertex(Schema.BaseType baseType){
        Vertex vertex = operateOnOpenGraph(() -> getTinkerPopGraph().addVertex(baseType.name()));
        vertex.property(Schema.ConceptProperty.ID.name(), vertex.id().toString());
        return vertex;
    }

    private Vertex putVertex(TypeLabel label, Schema.BaseType baseType){
        Vertex vertex;
        Optional<Integer> id = getId(label);
        ConceptImpl concept = null;
        if(id.isPresent()) concept = getConcept(Schema.ConceptProperty.TYPE_ID, id.get());
        if(concept == null) {
            vertex = addTypeVertex(getNextTypeId(), label, baseType);
        } else {
            if(!baseType.equals(concept.getBaseType())) {
                throw new ConceptNotUniqueException(concept, label.getValue());
            }
            vertex = concept.getVertex();
        }
        return vertex;
    }

    /**
     * Adds a new type vertex which occupies a grakn id. This result in the grakn id count on the meta concept to be
     * incremented.
     *
     * @param label The label of the new type vertex
     * @param baseType The base type of the new type
     * @return The new type vertex
     */
    private Vertex addTypeVertex(Integer id, TypeLabel label, Schema.BaseType baseType){
        Vertex vertex = addVertex(baseType);
        vertex.property(Schema.ConceptProperty.TYPE_LABEL.name(), label.getValue());
        vertex.property(Schema.ConceptProperty.TYPE_ID.name(), id);
        return vertex;
    }

    /**
     * An operation on the graph which requires it to be open.
     *
     * @param supplier The operation to be performed on the graph
     * @throws GraphRuntimeException if the graph is closed.
     * @return The result of the operation on the graph.
     */
    private <X> X operateOnOpenGraph(Supplier<X> supplier){
        if(isClosed()){
            String reason = localClosedReason.get();
            if(reason == null){
                throw new GraphRuntimeException(ErrorMessage.GRAPH_CLOSED.getMessage(getKeyspace()));
            } else {
                throw new GraphRuntimeException(reason);
            }
        }

        return supplier.get();
    }

    @Override
    public EntityType putEntityType(String label) {
        return putEntityType(TypeLabel.of(label));
    }

    @Override
    public EntityType putEntityType(TypeLabel label) {
        return putType(label, Schema.BaseType.ENTITY_TYPE,
                v -> getElementFactory().buildEntityType(v, getMetaEntityType()));
    }

    private <T extends TypeImpl> T putType(TypeLabel label, Schema.BaseType baseType, Function<Vertex, T> factory){
        checkOntologyMutation();
        TypeImpl type = buildType(label, () -> factory.apply(putVertex(label, baseType)));

        T finalType = validateConceptType(type, baseType, () -> {
            throw new ConceptNotUniqueException(type, label.getValue());
        });

        //Automatic shard creation - If this type does not have a shard create one
        if(!Schema.MetaSchema.isMetaLabel(label) &&
                !type.getEdgesOfType(Direction.IN, Schema.EdgeLabel.SHARD).findAny().isPresent()){
            type.createShard();
        }

        return finalType;
    }

    private <T extends Concept> T validateConceptType(Concept concept, Schema.BaseType baseType, Supplier<T> invalidHandler){
        if(concept != null && baseType.getClassType().isInstance(concept)){
            //noinspection unchecked
            return (T) concept;
        } else {
            return invalidHandler.get();
        }
    }

    /**
     * A helper method which either retrieves the type from the cache or builds it using a provided supplier
     *
     * @param label The label of the type to retrieve or build
     * @param dbBuilder A method which builds the type via a DB read or write
     *
     * @return The type which was either cached or built via a DB read or write
     */
    private TypeImpl buildType(TypeLabel label, Supplier<TypeImpl> dbBuilder){
        if(getConceptLog().isTypeCached(label)){
            return getConceptLog().getCachedType(label);
        } else {
            return dbBuilder.get();
        }
    }

    @Override
    public RelationType putRelationType(String label) {
        return putRelationType(TypeLabel.of(label));
    }

    @Override
    public RelationType putRelationType(TypeLabel label) {
        return putType(label, Schema.BaseType.RELATION_TYPE,
                v -> getElementFactory().buildRelationType(v, getMetaRelationType(), Boolean.FALSE)).asRelationType();
    }

    RelationType putRelationTypeImplicit(TypeLabel label) {
        return putType(label, Schema.BaseType.RELATION_TYPE,
                v -> getElementFactory().buildRelationType(v, getMetaRelationType(), Boolean.TRUE)).asRelationType();
    }

    @Override
    public RoleType putRoleType(String label) {
        return putRoleType(TypeLabel.of(label));
    }

    @Override
    public RoleType putRoleType(TypeLabel label) {
        return putType(label, Schema.BaseType.ROLE_TYPE,
                v -> getElementFactory().buildRoleType(v, getMetaRoleType(), Boolean.FALSE)).asRoleType();
    }

    RoleType putRoleTypeImplicit(TypeLabel label) {
        return putType(label, Schema.BaseType.ROLE_TYPE,
                v -> getElementFactory().buildRoleType(v, getMetaRoleType(), Boolean.TRUE)).asRoleType();
    }

    @Override
    public <V> ResourceType<V> putResourceType(String label, ResourceType.DataType<V> dataType) {
        return putResourceType(TypeLabel.of(label), dataType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> ResourceType<V> putResourceType(TypeLabel label, ResourceType.DataType<V> dataType) {
        @SuppressWarnings("unchecked")
        ResourceType<V> resourceType = putType(label, Schema.BaseType.RESOURCE_TYPE,
                v -> getElementFactory().buildResourceType(v, getMetaResourceType(), dataType)).asResourceType();

        //These checks is needed here because caching will return a type by label without checking the datatype
        if(Schema.MetaSchema.isMetaLabel(label)) {
            throw new ConceptException(ErrorMessage.META_TYPE_IMMUTABLE.getMessage(label));
        } else if(!dataType.equals(resourceType.getDataType())){
            throw new InvalidConceptValueException(ErrorMessage.IMMUTABLE_VALUE.getMessage(resourceType.getDataType(), resourceType, dataType, Schema.ConceptProperty.DATA_TYPE.name()));
        }

        return resourceType;
    }

    @Override
    public RuleType putRuleType(String label) {
        return putRuleType(TypeLabel.of(label));
    }

    @Override
    public RuleType putRuleType(TypeLabel label) {
        return putType(label, Schema.BaseType.RULE_TYPE,
                v ->  getElementFactory().buildRuleType(v, getMetaRuleType()));
    }

    //------------------------------------ Lookup
    /**
     * Looks up concept by using id against vertex ids. Does not use the index.
     * This is primarily used to fix duplicates when indicies cannot be relied on.
     *
     * @param id The id of the concept which should match the vertex id
     * @return The concept if it exists.
     */
    public <T extends Concept> T getConceptRawId(Object id) {
        GraphTraversal<Vertex, Vertex> traversal = getTinkerPopGraph().traversal().V(id);
        if (traversal.hasNext()) {
            return getElementFactory().buildConcept(traversal.next());
        } else {
            return null;
        }
    }

    @Override
    public <T extends Concept> T getConcept(ConceptId id) {
        if(getConceptLog().isConceptCached(id)){
            return getConceptLog().getCachedConcept(id);
        } else {
            return getConcept(Schema.ConceptProperty.ID, id.getValue());
        }
    }
    private <T extends Type> T getType(TypeLabel label, Schema.BaseType baseType){
        Type type = buildType(label, ()-> {
            Optional<Integer> id = getId(label);
            if (id.isPresent()) {
                return getType(id.get());
            } else {
                return null;
            }
        });
        return validateConceptType(type, baseType, () -> null);
    }
    private <T extends Type> T getType(int id){
        return getConcept(Schema.ConceptProperty.TYPE_ID, id);
    }

    @Override
    public <V> Collection<Resource<V>> getResourcesByValue(V value) {
        if(value == null) return Collections.emptySet();

        //Make sure you trying to retrieve supported data type
        if(!ResourceType.DataType.SUPPORTED_TYPES.containsKey(value.getClass().getName())){
            String supported = ResourceType.DataType.SUPPORTED_TYPES.keySet().stream().collect(Collectors.joining(","));
            throw new InvalidConceptValueException(ErrorMessage.INVALID_DATATYPE.getMessage(value.getClass().getName(), supported));
        }

        HashSet<Resource<V>> resources = new HashSet<>();
        ResourceType.DataType dataType = ResourceType.DataType.SUPPORTED_TYPES.get(value.getClass().getTypeName());

        //noinspection unchecked
        getConcepts(dataType.getConceptProperty(), dataType.getPersistenceValue(value)).forEach(concept -> {
            if(concept != null && concept.isResource()) {
                //noinspection unchecked
                resources.add(concept.asResource());
            }
        });

        return resources;
    }

    @Override
    public <T extends Type> T getType(TypeLabel label) {
        return getType(label, Schema.BaseType.TYPE);
    }

    @Override
    public EntityType getEntityType(String label) {
        return getType(TypeLabel.of(label), Schema.BaseType.ENTITY_TYPE);
    }

    @Override
    public RelationType getRelationType(String label) {
        return getType(TypeLabel.of(label), Schema.BaseType.RELATION_TYPE);
    }

    @Override
    public <V> ResourceType<V> getResourceType(String label) {
        return getType(TypeLabel.of(label), Schema.BaseType.RESOURCE_TYPE);
    }

    @Override
    public RoleType getRoleType(String label) {
        return getType(TypeLabel.of(label), Schema.BaseType.ROLE_TYPE);
    }

    @Override
    public RuleType getRuleType(String label) {
        return getType(TypeLabel.of(label), Schema.BaseType.RULE_TYPE);
    }

    @Override
    public Type getMetaConcept() {
        return getType(Schema.MetaSchema.CONCEPT.getId());
    }

    @Override
    public RelationType getMetaRelationType() {
        return getType(Schema.MetaSchema.RELATION.getId());
    }

    @Override
    public RoleType getMetaRoleType() {
        return getType(Schema.MetaSchema.ROLE.getId());
    }

    @Override
    public ResourceType getMetaResourceType() {
        return getType(Schema.MetaSchema.RESOURCE.getId());
    }

    @Override
    public EntityType getMetaEntityType() {
        return getType(Schema.MetaSchema.ENTITY.getId());
    }

    @Override
    public RuleType getMetaRuleType(){
        return getType(Schema.MetaSchema.RULE.getId());
    }

    @Override
    public RuleType getMetaRuleInference() {
        return getType(Schema.MetaSchema.INFERENCE_RULE.getId());
    }

    @Override
    public RuleType getMetaRuleConstraint() {
        return getType(Schema.MetaSchema.CONSTRAINT_RULE.getId());
    }

    //-----------------------------------------------Casting Functionality----------------------------------------------
    //------------------------------------ Construction
    private CastingImpl addCasting(RoleTypeImpl role, InstanceImpl rolePlayer){
        CastingImpl casting = getElementFactory().buildCasting(addVertex(Schema.BaseType.CASTING), role.currentShard()).setHash(role, rolePlayer);
        if(rolePlayer != null) {
            EdgeImpl castingToRolePlayer = addEdge(casting, rolePlayer, Schema.EdgeLabel.ROLE_PLAYER); // Casting to RolePlayer
            castingToRolePlayer.setProperty(Schema.EdgeProperty.ROLE_TYPE_ID, role.getTypeId());
        }
        return casting;
    }
    CastingImpl addCasting(RoleTypeImpl role, InstanceImpl rolePlayer, RelationImpl relation){
        CastingImpl foundCasting  = null;
        if(rolePlayer != null) {
            foundCasting = getCasting(role, rolePlayer);
        }

        if(foundCasting == null){
            foundCasting = addCasting(role, rolePlayer);
        }

        // Relation To Casting

        EdgeImpl relationToCasting = relation.putEdge(foundCasting, Schema.EdgeLabel.CASTING);
        relationToCasting.setProperty(Schema.EdgeProperty.ROLE_TYPE_ID, role.getTypeId());
        getConceptLog().trackConceptForValidation(relation); //The relation is explicitly tracked so we can look them up without committing

        //TODO: Only execute this if we need to. I.e if the above relation.putEdge() actually added a new edge.
        if(rolePlayer != null) putShortcutEdge(rolePlayer, relation, role);

        return foundCasting;
    }

    private CastingImpl getCasting(RoleTypeImpl role, InstanceImpl rolePlayer){
        return getConcept(Schema.ConceptProperty.INDEX, CastingImpl.generateNewHash(role, rolePlayer));
    }

    private void putShortcutEdge(Instance toInstance, Relation fromRelation, RoleType roleType){
        boolean exists  = getTinkerPopGraph().traversal().V(fromRelation.getId().getRawValue()).
                outE(Schema.EdgeLabel.SHORTCUT.getLabel()).
                has(Schema.EdgeProperty.RELATION_TYPE_ID.name(), fromRelation.type().getTypeId()).
                has(Schema.EdgeProperty.ROLE_TYPE_ID.name(), roleType.getTypeId()).inV().
                hasId(toInstance.getId().getRawValue()).hasNext();

        if(!exists){
            EdgeImpl edge = addEdge(fromRelation, toInstance, Schema.EdgeLabel.SHORTCUT);
            edge.setProperty(Schema.EdgeProperty.RELATION_TYPE_ID, fromRelation.type().getTypeId());
            edge.setProperty(Schema.EdgeProperty.ROLE_TYPE_ID, roleType.getTypeId());
        }
    }

    private RelationImpl getRelation(RelationType relationType, Map<RoleType, Set<Instance>> roleMap){
        String hash = generateNewHash(relationType, roleMap);
        RelationImpl concept = getConceptLog().getCachedRelation(hash);

        if(concept == null) {
            concept = getConcept(Schema.ConceptProperty.INDEX, hash);
        }
        return concept;
    }

    /**
     * Clears the graph completely.
     */
    @Override
    public void clear() {
        innerClear();
    }

    private void innerClear(){
        clearGraph();
        closeTransaction(ErrorMessage.CLOSED_CLEAR.getMessage());
    }

    //This is overridden by vendors for more efficient clearing approaches
    protected void clearGraph(){
        operateOnOpenGraph(() -> getTinkerPopGraph().traversal().V().drop().iterate());
    }

    @Override
    public void closeSession(){
        try {
            getTinkerPopGraph().close();
            localClosedReason.set(ErrorMessage.SESSION_CLOSED.getMessage(getKeyspace()));
        } catch (Exception e) {
            throw new GraphRuntimeException("Unable to close graph [" + getKeyspace() + "]", e);
        }
    }

    @Override
    public void close(){
        close(false, false);
    }

    @Override
    public void abort(){
        close();
    }

    @Override
    public void commit() throws GraknValidationException{
        close(true, true);
    }

    private Optional<String> close(boolean commitRequired, boolean submitLogs){
        Optional<String> logs = Optional.empty();
        if(isClosed()) return logs;
        String closeMessage = ErrorMessage.GRAPH_CLOSED_ON_ACTION.getMessage("closed", getKeyspace());

        try{
            if(commitRequired) {
                closeMessage = ErrorMessage.GRAPH_CLOSED_ON_ACTION.getMessage("committed", getKeyspace());
                logs = commitWithLogs();
                if(logs.isPresent() && submitLogs) {
                    LOG.debug("Response from engine [" + EngineCommunicator.contactEngine(getCommitLogEndPoint(), REST.HttpConn.POST_METHOD, logs.get()) + "]");
                }
                getConceptLog().writeToCentralCache(true);
            } else {
                getConceptLog().writeToCentralCache(isReadOnly());
            }
        } finally {
            closeTransaction(closeMessage);
        }
        return logs;
    }

    private void closeTransaction(String closedReason){
        try {
            graph.tx().close();
        } catch (UnsupportedOperationException e) {
            //Ignored for Tinker
        } finally {
            localClosedReason.set(closedReason);
            localIsOpen.remove();
            localConceptLog.remove();
        }
    }

    /**
     * Commits to the graph without submitting any commit logs.
     *
     * @throws GraknValidationException when the graph does not conform to the object concept
     */
    @Override
    public Optional<String> commitNoLogs() throws GraknValidationException {
        return close(true, false);
    }

    private Optional<String> commitWithLogs() throws GraknValidationException {
        validateGraph();

        boolean submissionNeeded = !getConceptLog().getInstanceCount().isEmpty() ||
                !getConceptLog().getModifiedCastings().isEmpty() ||
                !getConceptLog().getModifiedResources().isEmpty();
        JSONObject conceptLog = getConceptLog().getFormattedLog();

        LOG.trace("Graph is valid. Committing graph . . . ");
        commitTransactionInternal();

        //TODO: Kill when analytics no longer needs this
        GraknSparkComputer.refresh();

        LOG.trace("Graph committed.");

        //No post processing should ever be done for the system keyspace
        if(!keyspace.equalsIgnoreCase(SystemKeyspace.SYSTEM_GRAPH_NAME) && submissionNeeded) {
            return Optional.of(conceptLog.toString());
        }
        return Optional.empty();
    }

    void commitTransactionInternal(){
        try {
            getTinkerPopGraph().tx().commit();
        } catch (UnsupportedOperationException e){
            //IGNORED
        }
    }

    void validateGraph() throws GraknValidationException {
        Validator validator = new Validator(this);
        if (!validator.validate()) {
            List<String> errors = validator.getErrorsFound();
            StringBuilder error = new StringBuilder();
            error.append(ErrorMessage.VALIDATION.getMessage(errors.size()));
            for (String s : errors) {
                error.append(s);
            }
            throw new GraknValidationException(error.toString());
        }
    }

    private String getCommitLogEndPoint(){
        if(Grakn.IN_MEMORY.equals(engine)) {
            return Grakn.IN_MEMORY;
        }
        return engine + REST.WebPath.COMMIT_LOG_URI + "?" + REST.Request.KEYSPACE_PARAM + "=" + keyspace;
    }

    public void validVertex(Vertex vertex){
        if(vertex == null) {
            throw new IllegalStateException("The provided vertex is null");
        }
    }

    //------------------------------------------ Fixing Code for Postprocessing ----------------------------------------
    /**
     * Merges the provided duplicate castings.
     *
     * @param castingVertexIds The vertex Ids of the duplicate castings
     * @return if castings were merged, a commit is required and the casting index exists
     */
    @Override
    public boolean fixDuplicateCastings(String index, Set<ConceptId> castingVertexIds){
        Set<CastingImpl> duplicated = castingVertexIds.stream()
                .map(id -> this.<CastingImpl>getConceptRawId(id.getValue()))
                //filter non-null, will be null if previously deleted/merged
                .filter(Objects::nonNull)
                .collect(toSet());

        //This is done to ensure we merge into the indexed casting. Needs to be cleaned up though
        CastingImpl mainCasting = getConcept(Schema.ConceptProperty.INDEX, index, true);
        duplicated.remove(mainCasting);

        if(duplicated.size() > 0){
            //Fix the duplicates
            Set<Relation> duplicateRelations = mergeCastings(mainCasting, duplicated);

            //Remove Redundant Relations
            duplicateRelations.forEach(relation -> ((ConceptImpl) relation).deleteNode());

            //Restore the index
            String newIndex = mainCasting.getIndex();
            mainCasting.getVertex().property(Schema.ConceptProperty.INDEX.name(), newIndex);

            return true;
        }

        return false;
    }

    /**
     *
     * @param mainCasting The main casting to absorb all of the edges
     * @param castings The castings to whose edges will be transferred to the main casting and deleted.
     * @return A set of possible duplicate relations.
     */
    private Set<Relation> mergeCastings(CastingImpl mainCasting, Set<CastingImpl> castings){
        RoleType role = mainCasting.getRole();
        Set<Relation> relations = mainCasting.getRelations();
        Set<Relation> relationsToClean = new HashSet<>();

        for (CastingImpl otherCasting : castings) {
            //Transfer assertion edges
            for(Relation otherRelation : otherCasting.getRelations()){
                boolean transferEdge = true;

                //Check if an equivalent Relation is already connected to this casting. This could be a slow process
                for(Relation originalRelation: relations){
                    if(relationsEqual(originalRelation, otherRelation)){
                        relationsToClean.add(otherRelation);
                        transferEdge = false;
                        break;
                    }
                }

                //Perform the transfer
                if(transferEdge) {
                    EdgeImpl assertionToCasting = addEdge(otherRelation, mainCasting, Schema.EdgeLabel.CASTING);
                    assertionToCasting.setProperty(Schema.EdgeProperty.ROLE_TYPE_ID, role.getTypeId());
                    relations = mainCasting.getRelations();
                }
            }

            getConceptLog().removeConcept(otherCasting);
            getTinkerPopGraph().traversal().V(otherCasting.getId().getRawValue()).next().remove();
        }

        return relationsToClean;
    }

    /**
     *
     * @param mainRelation The main relation to compare
     * @param otherRelation The relation to compare it with
     * @return True if the roleplayers of the relations are the same.
     */
    private boolean relationsEqual(Relation mainRelation, Relation otherRelation){
        return mainRelation.allRolePlayers().equals(otherRelation.allRolePlayers()) &&
                mainRelation.type().equals(otherRelation.type());
    }

    /**
     *
     * @param resourceVertexIds The resource vertex ids which need to be merged.
     * @return True if a commit is required.
     */
    @Override
    public boolean fixDuplicateResources(String index, Set<ConceptId> resourceVertexIds){
        Set<ResourceImpl> duplicates = resourceVertexIds.stream()
                .map(id -> this.<ResourceImpl>getConceptRawId(id.getValue()))
                //filter non-null, will be null if previously deleted/merged
                .filter(Objects::nonNull)
                .collect(toSet());

        //The "main resource" will be the one returned by the index
        ResourceImpl<?> mainResource = getConcept(Schema.ConceptProperty.INDEX, index, true);
        duplicates.remove(mainResource);

        //Remove any resources associated with this index that are not the main resource
        if(duplicates.size() > 0){
            Iterator<ResourceImpl> it = duplicates.iterator();

            while(it.hasNext()){
                ResourceImpl<?> otherResource = it.next();
                Collection<Relation> otherRelations = otherResource.relations();

                //Delete the shortcut edges of the resource we going to delete.
                //This is so we can copy them uniquely later
                otherResource.getEdgesOfType(Direction.BOTH, Schema.EdgeLabel.SHORTCUT).forEach(EdgeImpl::delete);

                //Copy the actual relation
                for (Relation otherRelation : otherRelations) {
                    copyRelation(mainResource, otherResource, (RelationImpl) otherRelation);
                }

                //Delete the node and it's castings directly so we don't accidentally delete copied relations
                otherResource.castings().forEach(ConceptImpl::deleteNode);
                otherResource.deleteNode();
            }

            //Restore the index
            String newIndex = mainResource.getIndex();
            mainResource.getVertex().property(Schema.ConceptProperty.INDEX.name(), newIndex);

            return true;
        }

        return false;
    }

    /**
     *
     * @param main The main instance to possibly acquire a new relation
     * @param other The other instance which already posses the relation
     * @param otherRelation The other relation to potentially be absorbed
     */
    private void copyRelation(ResourceImpl main, ResourceImpl<?> other, RelationImpl otherRelation){
        RelationType relationType = otherRelation.type();
        Set<RoleTypeImpl> roleTypesOfResource = new HashSet<>(); //All the role types which the resource must play by the end
        Map<RoleType, Set<Instance>> allRolePlayers = otherRelation.allRolePlayers();

        //Replace all occurrences of other with main. That we we can quickly find out if the relation on main exists
        for (Map.Entry<RoleType, Set<Instance>> allRolePlayerEntries : allRolePlayers.entrySet()) {

            Iterator<Instance> it = allRolePlayerEntries.getValue().iterator();
            while (it.hasNext()){
                Instance instance = it.next();
                if(instance.isResource() && instance.asResource().getValue().equals(other.getValue())){//If the values are the same replace with main
                    it.remove();
                    allRolePlayerEntries.getValue().add(main);
                    roleTypesOfResource.add((RoleTypeImpl) allRolePlayerEntries.getKey());
                }
            }
        }

        //See if a duplicate relation already exists
        RelationImpl foundRelation = getRelation(relationType, allRolePlayers);

        if(foundRelation != null){//If it exists delete the other one
            otherRelation.deleteNode(); //Raw deletion because the castings should remain
        } else { //If it doesn't exist transfer the edge to the relevant casting node
            foundRelation = otherRelation;
            roleTypesOfResource.forEach(roleType -> addCasting(roleType, main, otherRelation));
        }

        //Explicitly track this new relation so we don't create duplicates
        String newHash = generateNewHash(relationType, allRolePlayers);
        getConceptLog().getModifiedRelations().put(newHash, foundRelation);
    }

    @Override
    public void updateTypeShards(Map<TypeLabel, Long> typeCounts){
       typeCounts.entrySet().forEach(entry -> {
           if(entry.getValue() != 0) {
               TypeImpl type = getType(entry.getKey());

               long newValue = type.getInstanceCount() + entry.getValue();
               if(newValue < shardingFactor) {
                   type.setInstanceCount(type.getInstanceCount() + entry.getValue());
               } else {
                   //TODO: Maintain the count properly. We reset so we can split with simpler logic
                   type.setInstanceCount(0L);
                   type.createShard();
               }


           }
       });
    }
}
