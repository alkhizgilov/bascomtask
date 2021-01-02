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
package com.ebay.bascomtask.core;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.ebay.bascomtask.core.UberTask.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests configuration of TaskRunners.
 *
 * @author Brendan McCarthy
 */
public class TaskRunnerTest {

    private final AtomicInteger count = new AtomicInteger();
    private final Map<Key, Integer> ordering = new ConcurrentHashMap<>();
    private Orchestrator $;

    static class Key {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key key = (Key) o;
            return Objects.equals(runner, key.runner) && Objects.equals(taskName, key.taskName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runner, taskName);
        }

        final MockRunner runner;
        final String taskName;

        Key(MockRunner runner, String taskName) {
            this.runner = runner;
            this.taskName = taskName;
        }
    }

    private static String ord(int x) {
        switch (x) {
            case 0:
                return "first";
            case 1:
                return "second";
            case 2:
                return "third";
            default:
                throw new RuntimeException("Ord too big: " + x);
        }
    }

    class MockRunner implements TaskRunner {
        final AtomicInteger beforeHits = new AtomicInteger(0);
        final AtomicInteger execSameThreadHits = new AtomicInteger(0);
        final AtomicInteger execDiffThreadHits = new AtomicInteger(0);
        final AtomicInteger completedHits = new AtomicInteger(0);
        final AtomicBoolean matched = new AtomicBoolean(false);
        private final String name;

        MockRunner(int expPos) {
            this.name = ord(expPos);
        }

        @Override
        public String toString() {
            return "MockRunner(" + name + ")";
        }

        @Override
        public Object before(TaskRun taskRun) {
            beforeHits.incrementAndGet();
            ordering.clear();
            return matched;
        }

        @Override
        public Object executeTaskMethod(TaskRun taskRun, Thread parentThread, Object fromBefore) {
            int order = count.getAndIncrement();
            ordering.put(new Key(this, taskRun.getName()), order);
            AtomicInteger which = parentThread == Thread.currentThread() ? execSameThreadHits : execDiffThreadHits;
            which.incrementAndGet();
            AtomicBoolean ab = (AtomicBoolean) fromBefore;
            ab.set(true);
            return taskRun.run();
        }

        @Override
        public void onComplete(TaskRun taskRun, Object fromBefore, boolean doneOnExit) {
            completedHits.incrementAndGet();
        }

        void verify(int exec, int execSame, int execDiff) {
            assertEquals(say("Before"), exec, beforeHits.get());
            assertEquals(say("ExecSame"), execSame, execSameThreadHits.get());
            assertEquals(say("ExecDiff"), execDiff, execDiffThreadHits.get());
            assertEquals(say("Completed"), exec, completedHits.get());
            assertTrue(say("Matched?"), matched.get());
        }

        private String say(String s) {
            return s + "(" + name + ")";
        }
    }

    /**
     * Verifies that for all tasks executed, the first runner was executed before the second
     *
     * @param r1 first runner
     * @param r2 second runner
     */
    private void verifyOrder(MockRunner r1, MockRunner r2) {
        List<String> badTaskNames = ordering.keySet().stream()
                .map(key -> key.taskName).distinct()
                .filter(tn -> !ordered(tn, r1, r2))
                .collect(Collectors.toList());
        assertEquals(0, badTaskNames.size());
    }

    private boolean ordered(String taskName, MockRunner r1, MockRunner r2) {
        return ordering.get(new Key(r1, taskName)) < ordering.get(new Key(r2, taskName));
    }

    @Before
    public void before() {
        count.set(0);
        GlobalConfig.INSTANCE.removeAllTaskRunners();
        $ = new Engine();
    }

    @Test
    public void oneRunner() throws Exception {
        MockRunner mockRunner = new MockRunner(0);
        GlobalConfig.INSTANCE.firstInterceptWith(mockRunner);

        $.task(task()).ret(1).get();
        mockRunner.verify(1, 1, 0);
    }

    @Test
    public void twoRunners() throws Exception {
        MockRunner firstMockRunner = new MockRunner(0);
        MockRunner secondMockRunner = new MockRunner(1);
        GlobalConfig.INSTANCE.firstInterceptWith(firstMockRunner);
        GlobalConfig.INSTANCE.lastInterceptWith(secondMockRunner);

        $.task(task()).ret(1).get();
        firstMockRunner.verify(1, 1, 0);
        secondMockRunner.verify(1, 1, 0);

        verifyOrder(firstMockRunner, secondMockRunner);
    }

    private void run3(CommonConfig m1, CommonConfig m2, CommonConfig m3) throws Exception {
        MockRunner firstMockRunner = new MockRunner(0);
        MockRunner secondMockRunner = new MockRunner(1);
        MockRunner thirdMockRunner = new MockRunner(2);
        m1.lastInterceptWith(secondMockRunner);
        m2.lastInterceptWith(thirdMockRunner);
        m3.firstInterceptWith(firstMockRunner);

        $.task(task()).ret(1).get();
        firstMockRunner.verify(1, 1, 0);
        secondMockRunner.verify(1, 1, 0);
        thirdMockRunner.verify(1, 1, 0);

        verifyOrder(firstMockRunner, secondMockRunner);
        verifyOrder(firstMockRunner, thirdMockRunner);
        verifyOrder(secondMockRunner, thirdMockRunner);
    }


    @Test
    public void globalGlobalLocal() throws Exception {
        run3(GlobalConfig.INSTANCE, GlobalConfig.INSTANCE, $);
    }

    @Test
    public void localLocalLocal() throws Exception {
        run3($, $, $);
    }

    @Test
    public void localGlobalLocal() throws Exception {
        run3($, GlobalConfig.INSTANCE, $);
    }

    @Test
    public void parV() throws Exception {
        MockRunner firstMockRunner = new MockRunner(0);
        MockRunner secondMockRunner = new MockRunner(1);
        GlobalConfig.INSTANCE.firstInterceptWith(firstMockRunner);
        GlobalConfig.INSTANCE.lastInterceptWith(secondMockRunner);

        CompletableFuture<Integer> left = $.task(task()).name("left").ret(1);
        CompletableFuture<Integer> right = $.task(task()).name("right").ret(2);
        CompletableFuture<Integer> add = $.task(task()).name("bottom").add(left, (right));
        assertEquals((Integer) 3, add.get());

        firstMockRunner.verify(3, 2, 1);
        secondMockRunner.verify(3, 2, 1);

        verifyOrder(firstMockRunner, secondMockRunner);
    }

    @Test
    public void addRemRunners() throws Exception {
        MockRunner gr1 = new MockRunner(0);
        MockRunner gr2 = new MockRunner(0);
        MockRunner or1 = new MockRunner(0);
        MockRunner or2 = new MockRunner(0);
        GlobalConfig.INSTANCE.firstInterceptWith(gr1);
        GlobalConfig.INSTANCE.firstInterceptWith(gr2);

        $.firstInterceptWith(or1);
        $.firstInterceptWith(or2);

        $.task(task()).ret(1).get();
        gr1.verify(1, 1, 0);
        gr2.verify(1, 1, 0);
        or1.verify(1, 1, 0);
        or2.verify(1, 1, 0);

        GlobalConfig.INSTANCE.removeInterceptor(gr1);
        $.removeInterceptor(or1);

        $.task(task()).ret(1).get();

        gr1.verify(1, 1, 0);
        gr2.verify(2, 2, 0);
        or1.verify(1, 1, 0);
        or2.verify(2, 2, 0);
    }
}