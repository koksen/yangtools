/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.parser.stmt.reactor;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import javax.annotation.Nonnull;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.SimpleDateFormatUtil;
import org.opendaylight.yangtools.yang.common.YangVersion;
import org.opendaylight.yangtools.yang.model.api.ModuleIdentifier;
import org.opendaylight.yangtools.yang.model.api.meta.DeclaredStatement;
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement;
import org.opendaylight.yangtools.yang.model.api.meta.IdentifierNamespace;
import org.opendaylight.yangtools.yang.model.repo.api.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.repo.api.StatementParserMode;
import org.opendaylight.yangtools.yang.parser.spi.meta.DerivedNamespaceBehaviour;
import org.opendaylight.yangtools.yang.parser.spi.meta.ModelProcessingPhase;
import org.opendaylight.yangtools.yang.parser.spi.meta.MutableStatement;
import org.opendaylight.yangtools.yang.parser.spi.meta.NamespaceBehaviour;
import org.opendaylight.yangtools.yang.parser.spi.meta.NamespaceBehaviour.NamespaceStorageNode;
import org.opendaylight.yangtools.yang.parser.spi.meta.NamespaceBehaviour.StorageNodeType;
import org.opendaylight.yangtools.yang.parser.spi.meta.NamespaceNotAvailableException;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.opendaylight.yangtools.yang.parser.spi.meta.SomeModifiersUnresolvedException;
import org.opendaylight.yangtools.yang.parser.spi.meta.StatementSupport;
import org.opendaylight.yangtools.yang.parser.spi.meta.StatementSupportBundle;
import org.opendaylight.yangtools.yang.parser.spi.source.SourceException;
import org.opendaylight.yangtools.yang.parser.spi.source.StatementStreamSource;
import org.opendaylight.yangtools.yang.parser.spi.source.SupportedFeaturesNamespace;
import org.opendaylight.yangtools.yang.parser.spi.source.SupportedFeaturesNamespace.SupportedFeatures;
import org.opendaylight.yangtools.yang.parser.spi.validation.ValidationBundlesNamespace;
import org.opendaylight.yangtools.yang.parser.spi.validation.ValidationBundlesNamespace.ValidationBundleType;
import org.opendaylight.yangtools.yang.parser.stmt.reactor.SourceSpecificContext.PhaseCompletionProgress;
import org.opendaylight.yangtools.yang.parser.stmt.rfc6020.RecursiveObjectLeaker;
import org.opendaylight.yangtools.yang.parser.stmt.rfc6020.Utils;
import org.opendaylight.yangtools.yang.parser.stmt.rfc6020.effective.EffectiveSchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BuildGlobalContext extends NamespaceStorageSupport implements NamespaceBehaviour.Registry {
    private static final Logger LOG = LoggerFactory.getLogger(BuildGlobalContext.class);

    private static final List<ModelProcessingPhase> PHASE_EXECUTION_ORDER = ImmutableList
            .<ModelProcessingPhase> builder().add(ModelProcessingPhase.SOURCE_PRE_LINKAGE)
            .add(ModelProcessingPhase.SOURCE_LINKAGE).add(ModelProcessingPhase.STATEMENT_DEFINITION)
            .add(ModelProcessingPhase.FULL_DECLARATION).add(ModelProcessingPhase.EFFECTIVE_MODEL).build();

    private final Table<YangVersion, QName, StatementDefinitionContext<?, ?, ?>> definitions = HashBasedTable.create();
    private final Map<Class<?>, NamespaceBehaviourWithListeners<?, ?, ?>> supportedNamespaces = new HashMap<>();

    private final Map<ModelProcessingPhase, StatementSupportBundle> supports;
    private final Set<SourceSpecificContext> sources = new HashSet<>();
    private Set<SourceSpecificContext> libSources = new HashSet<>();

    private ModelProcessingPhase currentPhase = ModelProcessingPhase.INIT;
    private ModelProcessingPhase finishedPhase = ModelProcessingPhase.INIT;

    private final boolean enabledSemanticVersions;
    private final Set<YangVersion> supportedVersions;
    private final List<MutableStatement> mutableStatementsToSeal;

    BuildGlobalContext(final Map<ModelProcessingPhase, StatementSupportBundle> supports,
            final StatementParserMode statementParserMode, final Set<QName> supportedFeatures) {
        this(supports, ImmutableMap.of(), statementParserMode, supportedFeatures);
    }

    BuildGlobalContext(final Map<ModelProcessingPhase, StatementSupportBundle> supports,
            final Map<ValidationBundleType, Collection<?>> supportedValidation,
            final StatementParserMode statementParserMode, final Set<QName> supportedFeatures) {
        super();
        this.supports = Preconditions.checkNotNull(supports, "BuildGlobalContext#supports cannot be null");
        Preconditions.checkNotNull(statementParserMode, "Statement parser mode must not be null.");
        this.enabledSemanticVersions = statementParserMode == StatementParserMode.SEMVER_MODE;

        for (final Entry<ValidationBundleType, Collection<?>> validationBundle : supportedValidation.entrySet()) {
            addToNs(ValidationBundlesNamespace.class, validationBundle.getKey(), validationBundle.getValue());
        }

        if (supportedFeatures != null) {
            addToNs(SupportedFeaturesNamespace.class, SupportedFeatures.SUPPORTED_FEATURES,
                    ImmutableSet.copyOf(supportedFeatures));
        } else {
            LOG.warn("Set of supported features has not been provided, so all features are supported by default.");
        }
        this.supportedVersions = ImmutableSet.copyOf(supports.get(ModelProcessingPhase.INIT).getSupportedVersions());
        this.mutableStatementsToSeal = new ArrayList<>();
    }

    boolean isEnabledSemanticVersioning() {
        return enabledSemanticVersions;
    }

    StatementSupportBundle getSupportsForPhase(final ModelProcessingPhase currentPhase) {
        return supports.get(currentPhase);
    }

    void addSource(@Nonnull final StatementStreamSource source) {
        sources.add(new SourceSpecificContext(this, source));
    }

    void addLibSource(@Nonnull final StatementStreamSource libSource) {
        Preconditions.checkState(!isEnabledSemanticVersioning(),
                "Library sources are not supported in semantic version mode currently.");
        Preconditions.checkState(currentPhase == ModelProcessingPhase.INIT,
                "Add library source is allowed in ModelProcessingPhase.INIT only");
        libSources.add(new SourceSpecificContext(this, libSource));
    }

    @Override
    public StorageNodeType getStorageNodeType() {
        return StorageNodeType.GLOBAL;
    }

    @Override
    public NamespaceStorageNode getParentNamespaceStorage() {
        return null;
    }

    @Override
    public NamespaceBehaviour.Registry getBehaviourRegistry() {
        return this;
    }

    @Override
    public <K, V, N extends IdentifierNamespace<K, V>> NamespaceBehaviourWithListeners<K, V, N> getNamespaceBehaviour(
            final Class<N> type) {
        NamespaceBehaviourWithListeners<?, ?, ?> potential = supportedNamespaces.get(type);
        if (potential == null) {
            final NamespaceBehaviour<K, V, N> potentialRaw = supports.get(currentPhase).getNamespaceBehaviour(type);
            if (potentialRaw != null) {
                potential = createNamespaceContext(potentialRaw);
                supportedNamespaces.put(type, potential);
            } else {
                throw new NamespaceNotAvailableException("Namespace " + type + " is not available in phase "
                        + currentPhase);
            }
        }

        Verify.verify(type.equals(potential.getIdentifier()));
        /*
         * Safe cast, previous checkState checks equivalence of key from which
         * type argument are derived
         */
        return (NamespaceBehaviourWithListeners<K, V, N>) potential;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <K, V, N extends IdentifierNamespace<K, V>> NamespaceBehaviourWithListeners<K, V, N> createNamespaceContext(
            final NamespaceBehaviour<K, V, N> potentialRaw) {
        if (potentialRaw instanceof DerivedNamespaceBehaviour) {
            final VirtualNamespaceContext derivedContext = new VirtualNamespaceContext(
                    (DerivedNamespaceBehaviour) potentialRaw);
            getNamespaceBehaviour(((DerivedNamespaceBehaviour) potentialRaw).getDerivedFrom()).addDerivedNamespace(
                    derivedContext);
            return derivedContext;
        }
        return new SimpleNamespaceContext<>(potentialRaw);
    }

    StatementDefinitionContext<?, ?, ?> getStatementDefinition(final YangVersion version, final QName name) {
        StatementDefinitionContext<?, ?, ?> potential = definitions.get(version, name);
        if (potential == null) {
            final StatementSupport<?, ?, ?> potentialRaw = supports.get(currentPhase).getStatementDefinition(version,
                    name);
            if (potentialRaw != null) {
                potential = new StatementDefinitionContext<>(potentialRaw);
                definitions.put(version, name, potential);
            }
        }
        return potential;
    }

    EffectiveModelContext build() throws SourceException, ReactorException {
        for (final ModelProcessingPhase phase : PHASE_EXECUTION_ORDER) {
            startPhase(phase);
            loadPhaseStatements();
            completePhaseActions();
            endPhase(phase);
        }
        return transform();
    }

    private EffectiveModelContext transform() {
        Preconditions.checkState(finishedPhase == ModelProcessingPhase.EFFECTIVE_MODEL);
        final List<DeclaredStatement<?>> rootStatements = new ArrayList<>(sources.size());
        for (final SourceSpecificContext source : sources) {
            rootStatements.add(source.getRoot().buildDeclared());
        }
        return new EffectiveModelContext(rootStatements);
    }

    EffectiveSchemaContext buildEffective() throws ReactorException {
        for (final ModelProcessingPhase phase : PHASE_EXECUTION_ORDER) {
            startPhase(phase);
            loadPhaseStatements();
            completePhaseActions();
            endPhase(phase);
        }
        return transformEffective();
    }

    private SomeModifiersUnresolvedException propagateException(final SourceSpecificContext source,
            final RuntimeException cause) throws SomeModifiersUnresolvedException {
        final SourceIdentifier sourceId = Utils.createSourceIdentifier(source.getRoot());
        if (!(cause instanceof SourceException)) {
            /*
             * This should not be happening as all our processing should provide SourceExceptions.
             * We will wrap the exception to provide enough information to identify the problematic model,
             * but also emit a warning so the offending codepath will get fixed.
             */
            LOG.warn("Unexpected error processing source {}. Please file an issue with this model attached.",
                sourceId, cause);
        }

        throw new SomeModifiersUnresolvedException(currentPhase, sourceId, cause);
    }

    private EffectiveSchemaContext transformEffective() throws ReactorException {
        Preconditions.checkState(finishedPhase == ModelProcessingPhase.EFFECTIVE_MODEL);
        final List<DeclaredStatement<?>> rootStatements = new ArrayList<>(sources.size());
        final List<EffectiveStatement<?, ?>> rootEffectiveStatements = new ArrayList<>(sources.size());

        try {
            for (final SourceSpecificContext source : sources) {
                final RootStatementContext<?, ?, ?> root = source.getRoot();
                try {
                    rootStatements.add(root.buildDeclared());
                    rootEffectiveStatements.add(root.buildEffective());
                } catch (final RuntimeException ex) {
                    throw propagateException(source, ex);
                }
            }
        } finally {
            RecursiveObjectLeaker.cleanup();
        }

        sealMutableStatements();
        return new EffectiveSchemaContext(rootStatements, rootEffectiveStatements);
    }

    private void startPhase(final ModelProcessingPhase phase) {
        Preconditions.checkState(Objects.equals(finishedPhase, phase.getPreviousPhase()));
        startPhaseFor(phase, sources);
        startPhaseFor(phase, libSources);

        currentPhase = phase;
        LOG.debug("Global phase {} started", phase);
    }

    private void startPhaseFor(final ModelProcessingPhase phase, final Set<SourceSpecificContext> sources) {
        for (final SourceSpecificContext source : sources) {
            source.startPhase(phase);
        }
    }

    private void loadPhaseStatements() throws ReactorException {
        Preconditions.checkState(currentPhase != null);
        loadPhaseStatementsFor(sources);
        loadPhaseStatementsFor(libSources);
    }

    private void loadPhaseStatementsFor(final Set<SourceSpecificContext> sources) throws ReactorException {
        for (final SourceSpecificContext source : sources) {
            try {
                source.loadStatements();
            } catch (final RuntimeException ex) {
                throw propagateException(source, ex);
            }
        }
    }

    private SomeModifiersUnresolvedException addSourceExceptions(final List<SourceSpecificContext> sourcesToProgress) {
        boolean addedCause = false;
        SomeModifiersUnresolvedException buildFailure = null;
        for (final SourceSpecificContext failedSource : sourcesToProgress) {
            final Optional<SourceException> optSourceEx = failedSource.failModifiers(currentPhase);
            if (!optSourceEx.isPresent()) {
                continue;
            }

            final SourceException sourceEx = optSourceEx.get();
            // Workaround for broken logging implementations which ignore
            // suppressed exceptions
            final Throwable cause = sourceEx.getCause() != null ? sourceEx.getCause() : sourceEx;
            if (LOG.isDebugEnabled()) {
                LOG.error("Failed to parse YANG from source {}", failedSource, sourceEx);
            } else {
                LOG.error("Failed to parse YANG from source {}: {}", failedSource, cause.getMessage());
            }

            final Throwable[] suppressed = sourceEx.getSuppressed();
            if (suppressed.length > 0) {
                LOG.error("{} additional errors reported:", suppressed.length);

                int i = 1;
                for (final Throwable t : suppressed) {
                    // FIXME: this should be configured in the appender, really
                    if (LOG.isDebugEnabled()) {
                        LOG.error("Error {}: {}", i, t.getMessage(), t);
                    } else {
                        LOG.error("Error {}: {}", i, t.getMessage());
                    }

                    i++;
                }
            }

            if (!addedCause) {
                addedCause = true;
                final SourceIdentifier sourceId = Utils.createSourceIdentifier(failedSource.getRoot());
                buildFailure = new SomeModifiersUnresolvedException(currentPhase, sourceId, sourceEx);
            } else {
                buildFailure.addSuppressed(sourceEx);
            }
        }
        return buildFailure;
    }

    private void completePhaseActions() throws ReactorException {
        Preconditions.checkState(currentPhase != null);
        final List<SourceSpecificContext> sourcesToProgress = Lists.newArrayList(sources);
        if (!libSources.isEmpty()) {
            Preconditions.checkState(currentPhase == ModelProcessingPhase.SOURCE_PRE_LINKAGE,
                    "Yang library sources should be empty after ModelProcessingPhase.SOURCE_PRE_LINKAGE, "
                            + "but current phase was %s", currentPhase);
            sourcesToProgress.addAll(libSources);
        }

        boolean progressing = true;
        while (progressing) {
            // We reset progressing to false.
            progressing = false;
            final Iterator<SourceSpecificContext> currentSource = sourcesToProgress.iterator();
            while (currentSource.hasNext()) {
                final SourceSpecificContext nextSourceCtx = currentSource.next();
                try {
                    final PhaseCompletionProgress sourceProgress = nextSourceCtx.tryToCompletePhase(currentPhase);
                    switch (sourceProgress) {
                        case FINISHED:
                            currentSource.remove();
                            // Fallback to progress, since we were able to make
                            // progress in computation
                        case PROGRESS:
                            progressing = true;
                            break;
                        case NO_PROGRESS:
                            // Noop
                            break;
                        default:
                            throw new IllegalStateException("Unsupported phase progress " + sourceProgress);
                    }
                } catch (final RuntimeException ex) {
                    throw propagateException(nextSourceCtx, ex);
                }
            }
        }

        if (!libSources.isEmpty()) {
            final Set<SourceSpecificContext> requiredLibs = getRequiredSourcesFromLib();
            sources.addAll(requiredLibs);
            libSources = ImmutableSet.of();
            /*
             * We want to report errors of relevant sources only, so any others can
             * be removed.
             */
            sourcesToProgress.retainAll(sources);
        }

        if (!sourcesToProgress.isEmpty()) {
            final SomeModifiersUnresolvedException buildFailure = addSourceExceptions(sourcesToProgress);
            if (buildFailure != null) {
                throw buildFailure;
            }
        }
    }

    private Set<SourceSpecificContext> getRequiredSourcesFromLib() {
        Preconditions.checkState(currentPhase == ModelProcessingPhase.SOURCE_PRE_LINKAGE,
                "Required library sources can be collected only in ModelProcessingPhase.SOURCE_PRE_LINKAGE phase,"
                        + " but current phase was %s", currentPhase);
        final TreeBasedTable<String, Date, SourceSpecificContext> libSourcesTable = TreeBasedTable.create();
        for (final SourceSpecificContext libSource : libSources) {
            final ModuleIdentifier libSourceIdentifier = Preconditions.checkNotNull(libSource.getRootIdentifier());
            libSourcesTable.put(libSourceIdentifier.getName(), libSourceIdentifier.getRevision(), libSource);
        }

        final Set<SourceSpecificContext> requiredLibs = new HashSet<>();
        for (final SourceSpecificContext source : sources) {
            collectRequiredSourcesFromLib(libSourcesTable, requiredLibs, source);
        }
        return requiredLibs;
    }

    private void collectRequiredSourcesFromLib(
            final TreeBasedTable<String, Date, SourceSpecificContext> libSourcesTable,
            final Set<SourceSpecificContext> requiredLibs, final SourceSpecificContext source) {
        final Collection<ModuleIdentifier> requiredModules = source.getRequiredModules();
        for (final ModuleIdentifier requiredModule : requiredModules) {
            final SourceSpecificContext libSource = getRequiredLibSource(requiredModule, libSourcesTable);
            if (libSource != null && requiredLibs.add(libSource)) {
                collectRequiredSourcesFromLib(libSourcesTable, requiredLibs, libSource);
            }
        }
    }

    private SourceSpecificContext getRequiredLibSource(final ModuleIdentifier requiredModule,
            final TreeBasedTable<String, Date, SourceSpecificContext> libSourcesTable) {
        return requiredModule.getRevision() == SimpleDateFormatUtil.DEFAULT_DATE_IMP ? getLatestRevision(libSourcesTable
                .row(requiredModule.getName())) : libSourcesTable.get(requiredModule.getName(),
                requiredModule.getRevision());
    }

    private SourceSpecificContext getLatestRevision(final SortedMap<Date, SourceSpecificContext> sourceMap) {
        return sourceMap != null && !sourceMap.isEmpty() ? sourceMap.get(sourceMap.lastKey()) : null;
    }

    private void endPhase(final ModelProcessingPhase phase) {
        Preconditions.checkState(currentPhase == phase);
        finishedPhase = currentPhase;
        LOG.debug("Global phase {} finished", phase);
    }

    Set<SourceSpecificContext> getSources() {
        return sources;
    }

    public Set<YangVersion> getSupportedVersions() {
        return supportedVersions;
    }

    void addMutableStmtToSeal(final MutableStatement mutableStatement) {
        mutableStatementsToSeal.add(mutableStatement);
    }

    void sealMutableStatements() {
        for (final MutableStatement mutableStatement : mutableStatementsToSeal) {
            mutableStatement.seal();
        }
        mutableStatementsToSeal.clear();
    }
}
