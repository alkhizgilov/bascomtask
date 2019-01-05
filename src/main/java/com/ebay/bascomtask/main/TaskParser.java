/************************************************************************
Copyright 2018 eBay Inc.
Author/Developer: Brendan McCarthy
 
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
 
    https://www.apache.org/licenses/LICENSE-2.0
 
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
**************************************************************************/
package com.ebay.bascomtask.main;

import java.util.List;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import com.ebay.bascomtask.annotations.Ordered;
import com.ebay.bascomtask.annotations.PassThru;
import com.ebay.bascomtask.annotations.Rollback;
import com.ebay.bascomtask.annotations.Scope;
import com.ebay.bascomtask.annotations.Work;
import com.ebay.bascomtask.exceptions.InvalidTask;

/**
 * Performs any pre-processing of tasks that can be shared across orchestrator
 * runs to avoid the expense of doing so each time.
 * 
 * @author brendanmccarthy
 */
class TaskParser {

    private static TaskParser taskParser = new TaskParser();

    private Map<Class<?>, Task> map = new HashMap<>();

    /**
     * Returns the task representation of any java class, pre-processing it if
     * it does not already exist in the cache.
     * 
     * @param clazz
     * @return
     */
    static Task parse(Class<?> clazz) {
        return taskParser.parse2(clazz);
    }

    private synchronized Task parse2(Class<?> clazz) {
        Task task = map.get(clazz);
        if (task == null) {
            task = new Task(clazz);
            map.put(clazz,task);
            // ! Reparse only after map updated because of potential recursion
            // back to this method
            parse(task);
        }
        return task;
    }

    private void parse(Task task) {
        Class<?> cls = task.producesClass;
        do {
            for (Method method : cls.getDeclaredMethods()) {
                parse(task,method);
            }
            cls = cls.getSuperclass();
        }
        while (cls != null && cls != Object.class);
    }

    private void parse(Task task, Method method) {
        Call call = null;
        Work work = method.getAnnotation(Work.class);
        if (work != null) {
            call = new Call(task,method,work.scope(),work.light());
            task.workCalls.add(call);
        }
        PassThru passThru = method.getAnnotation(PassThru.class);
        if (passThru != null) {
            // @PassThru methods are always consider 'light'
            call = new Call(task,method,Scope.FREE,true);
            task.passThruCalls.add(call);
        }
        Rollback rollBack = method.getAnnotation(Rollback.class);
        if (rollBack != null) {
            call = new Call(task,method,Scope.FREE,rollBack.light());
            task.rollbackCalls.add(call);
        }
        
        if (call != null) {
            method.setAccessible(true); // Methods need not be public, allowing
                                        // for local classes
            //verifyAccess(method);
            //Class<?> rt = method.getReturnType();
            Annotation[][] parameterAnns = method.getParameterAnnotations();
            Type[] genericParameterTypes = method.getGenericParameterTypes();
            Class<?>[] pt = method.getParameterTypes();
            for (int i = 0; i < method.getParameterTypes().length; i++) {
                boolean isList = false;
                Type nextMethodParamType = genericParameterTypes[i];
                Class<?> nextMethodParamClass = pt[i];
                // If the parameter is a List<T>, treat it is a T but mark the
                // parameter as a list
                if (List.class.isAssignableFrom(nextMethodParamClass)) {
                    isList = true;
                    if (nextMethodParamType instanceof ParameterizedType) {
                        ParameterizedType genericType = (ParameterizedType) nextMethodParamType;
                        Type typeArg = genericType.getActualTypeArguments()[0];
                        nextMethodParamClass = (Class<?>) typeArg;
                    }
                }
                if (nextMethodParamClass.isPrimitive()) {
                    throw new InvalidTask.BadParam("Task method " + mn(method) + " has non-Object parameter of type "
                            + nextMethodParamClass.getSimpleName());
                }
                Task paramTask = parse2(nextMethodParamClass);
                boolean ordered = false;
                for (Annotation next: parameterAnns[i]) {
                    Class<? extends Annotation> at = next.annotationType();
                    if (Ordered.class.isAssignableFrom(at)) {
                        ordered = true;
                    }
                }
                Call.Param param = call.new Param(paramTask,i,isList,ordered);
                call.add(param);
                paramTask.backLink(param);
            }
        }
    }

    static String mn(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /*
    private void verifyAccess(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt != Void.TYPE && rt != Boolean.TYPE) {
            throw new InvalidTask.BadReturn("Task method " + mn(method) + " must return void or boolean");
        }
    }
    */
}
