/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.parser.spi.meta;

import com.google.common.base.Supplier;
import java.util.Collection;
import javax.annotation.Nonnull;
import org.opendaylight.yangtools.yang.model.api.meta.DeclaredStatement;
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement;
import org.opendaylight.yangtools.yang.model.api.meta.IdentifierNamespace;
import org.opendaylight.yangtools.yang.parser.spi.meta.StmtContext.Mutable;


/**
 * Builder for effective model inference action.
 *
 * Model inference action is core principle of transforming
 * declared model into effective model.
 *
 * Since YANG allows forward references, some inference actions
 * needs to be taken at later point, where reference is actually
 * resolved. Referenced objects are not retrieved directly
 * but are represented as {@link Prerequisite} (prerequisite) for
 * inference action to be taken.
 *
 * Some existing YANG statements are more complex and also object,
 * for which effective model may be inferred is also represented
 * as {@link Prerequisite} which once, when reference is available
 * will contain target context, which may be used for inference
 * action.
 *
 * <h2>Implementing inference action</h2>
 *
 * Effective inference action could always be splitted into two
 * separate tasks:
 * <ol>
 * <li>Declaration of inference action and its prerequisites</li>
 * <li>Execution of inference action</li>
 * </ol>
 * In order to declare inference action following steps needs
 * to be taken:
 *
 * <ol>
 * <li>Use {@link StmtContext.Mutable#newInferenceAction(ModelProcessingPhase)} to obtain
 * {@link ModelActionBuilder}.
 * <li>Use builder to specify concrete prerequisites of inference action
 * (other statements, values from identifier namespaces)
 * <li>Use builder to specify concrete set of nodes (or forward references to nodes)
 * which will inference action mutate.
 * <li>Use {@link #apply(InferenceAction)} with {@link InferenceAction} implementation
 * to register inference action.
 * </ol>
 *
 * Action will be executed when:
 * <ul>
 * <li> {@link InferenceAction#apply()} - all prerequisites (and declared forward references) are met,
 * action could dereference them and start applying changes.
 * </li>
 * <li>{@link InferenceAction#prerequisiteFailed(Collection)} - semantic parser finished all other satisfied
 * inference actions and some of declared prerequisites was still not met.
 * </li>
 * </ul>
 *
 * TODO: Insert real word example
 *
 * <h2>Design notes</h2>
 * {@link java.util.concurrent.Future} seems as viable and more standard
 * alternative to {@link Prerequisite}, but futures also carries
 * promise that resolution of it is carried in other
 * thread, which will actually put additional constraints on
 * semantic parser.
 *
 * Also listening on multiple futures is costly, so we opted
 * out of future and designed API, which later may introduce
 * futures.
 *
 */
public interface ModelActionBuilder {

    interface Prerequisite<T> extends Supplier<T> {

        /**
         *
         * Returns associated prerequisite once it is resolved.
         *
         * @return associated prerequisite once it is resolved.
         *
         */
        @Override
        T get();

        boolean isDone();
    }

    /**
     * User-defined inference action.
     *
     */
    interface InferenceAction {

        /**
         * Invoked once all prerequisites were met and forward references
         * were resolved and inference action should be applied.
         *
         * Implementors may do necessary changes to mutable objects
         * which were declared.
         *
         * @throws InferenceException If inference action can not be processed.
         *      Note that this exception be used for user to debug YANG sources,
         *      so should provide helpful context to fix issue in sources.
         */
        void apply() throws InferenceException;

        /**
         * Invoked once one of prerequisites was not met,
         * even after all other satifiable inference actions were processed.
         *
         * Implementors MUST throw {@link InferenceException} if semantic processing
         * of model should be stopped and failed.
         *
         * List of failed prerequisites should be used to select right message / error
         * type to debug problem in YANG sources.
         *
         * @throws InferenceException If inference action can not be processed.
         *      Note that this exception be used for user to debug YANG sources,
         *      so should provide helpful context to fix issue in sources.
         *
         * @param failed collection of prerequisites which were not met
         */
        void prerequisiteFailed(Collection<? extends Prerequisite<?>> failed) throws InferenceException;
    }

    @Nonnull <D extends DeclaredStatement<?>> Prerequisite<D> requiresDeclared(StmtContext<?,? extends D,?> context);

    @Nonnull <K, D extends DeclaredStatement<?>, N extends StatementNamespace<K, ? extends D, ?>> Prerequisite<D> requiresDeclared(StmtContext<?,?,?> context,Class<N> namespace, K key);

    @Nonnull <K, D extends DeclaredStatement<?>, N extends StatementNamespace<K, ? extends D, ?>> Prerequisite<StmtContext<?, D, ?>>requiresDeclaredCtx(StmtContext<?,?,?> context,Class<N> namespace, K key);

    @Nonnull <E extends EffectiveStatement<?,?>> Prerequisite<E> requiresEffective(StmtContext<?,?,? extends E> stmt);

    @Nonnull <K, E extends EffectiveStatement<?, ?>, N extends StatementNamespace<K, ?, ? extends E>> Prerequisite<E> requiresEffective(StmtContext<?,?,?> context,Class<N> namespace, K key);

    @Nonnull <K, E extends EffectiveStatement<?, ?>, N extends StatementNamespace<K, ?, ? extends E>> Prerequisite<StmtContext<?,?,E>> requiresEffectiveCtx(StmtContext<?,?,?> context,Class<N> namespace, K key);

    @Nonnull <N extends IdentifierNamespace<? ,?>> Prerequisite<Mutable<?,?,?>> mutatesNs(Mutable<?,?, ?> ctx, Class<N> namespace);

    @Nonnull <T extends Mutable<?,?,?>> Prerequisite<T> mutatesEffectiveCtx(T stmt);

    @Nonnull  <K,E extends EffectiveStatement<?,?>,N extends StatementNamespace<K, ?, ? extends E>> Prerequisite<Mutable<?,?,E>> mutatesEffectiveCtx(StmtContext<?,?,?> context,Class<N> namespace, K key);

    void apply(InferenceAction action) throws InferenceException;

    @Nonnull <A,D extends DeclaredStatement<A>,E extends EffectiveStatement<A, D>> Prerequisite<StmtContext<A, D, E>> requiresCtx(StmtContext<A, D, E> context, ModelProcessingPhase phase);

    @Nonnull <K, N extends StatementNamespace<K, ?, ? >> Prerequisite<StmtContext<?,?,?>> requiresCtx(StmtContext<?, ?, ?> context, Class<N> namespace, K key, ModelProcessingPhase phase);

    @Nonnull <C extends StmtContext.Mutable<?,?,?>, CT extends C> Prerequisite<C> mutatesCtx(CT root, ModelProcessingPhase phase);
}
