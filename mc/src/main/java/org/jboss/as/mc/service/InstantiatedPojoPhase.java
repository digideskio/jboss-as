/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.mc.service;

import org.jboss.as.mc.BeanState;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;

/**
 * MC pojo instantiated phase.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class InstantiatedPojoPhase extends AbstractPojoPhase {
    private Object bean;
    private InjectedValue<Joinpoint> instantiationJoinpoint = new InjectedValue<Joinpoint>();

    @Override
    protected BeanState getLifecycleState() {
        return BeanState.INSTANTIATED;
    }

    @Override
    protected AbstractPojoPhase createNextPhase() {
        return new ConfiguredPojoPhase();
    }

    public void start(StartContext context) throws StartException {
        try {
            bean = instantiationJoinpoint.getValue().dispatch();
        } catch (Throwable t) {
            throw new StartException(t);
        }
        super.start(context);
    }

    public Object getValue() throws IllegalStateException, IllegalArgumentException {
        return bean;
    }

    public InjectedValue<Joinpoint> getInstantiationJoinpoint() {
        return instantiationJoinpoint;
    }
}
