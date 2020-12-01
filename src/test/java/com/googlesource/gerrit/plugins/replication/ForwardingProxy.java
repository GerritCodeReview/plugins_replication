// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A ForwardingProxy creates a Proxy which forwards all method calls to its delegate except for
 * calls to methods which are implemented by its overrider's class.
 *
 * <p>Using this Proxy class makes it possible to use the delegate pattern on any interface without
 * having to implement any of the interface's methods which directly forward their calls to the
 * delegate. Using this is intended to make forwarding automated, easy, and less error prone by
 * making it possible to implement the delegate pattern with an overrider object which only
 * implements those methods which need overridden functionality and which will not directly forward
 * their calls to the delegate.
 *
 * <p>The overrider object will be assumed to not implement any default java Object methods which
 * are not overridden, as that would likely not be desirable behavior, and thus the Proxy will not
 * forward those methods to the overrider unless the overrider overrides them.
 *
 * <p>If an overrider needs to make calls to the delegate, this can be achieved by passing the
 * delegate into the overrider during construction.
 */
public class ForwardingProxy {
  protected static class Handler<T> implements InvocationHandler {
    protected T delegate;
    protected Object overrider;

    protected Handler(T delegate, Object overrider) {
      this.delegate = delegate;
      this.overrider = overrider;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      Method overriden = getOverriden(method);
      if (overriden != null) {
        return overriden.invoke(overrider, args);
      }
      return method.invoke(delegate, args);
    }

    protected Method getOverriden(Method method) {
      try {
        Method implementedByOverrider =
            overrider.getClass().getMethod(method.getName(), method.getParameterTypes());

        // Only allow defined (non java defaulted) methods to actually be overridden
        if (Object.class != implementedByOverrider.getDeclaringClass()) {
          return implementedByOverrider;
        }
      } catch (NoSuchMethodException | SecurityException e) {
      }
      return null;
    }
  }

  public static <T> T create(Class<T> toProxy, T delegate, Object overrider) {
    return (T)
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class[] {toProxy},
            new Handler<>(delegate, overrider));
  }
}
