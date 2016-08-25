/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.apache.beam.runners.direct.DirectRunner.DirectPipelineResult;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.runners.PipelineRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.util.IllegalMutationException;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.TypeDescriptor;
import com.google.common.collect.ImmutableMap;
import com.fasterxml.jackson.annotation.JsonValue;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.internal.matchers.ThrowableMessageMatcher;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for basic {@link DirectRunner} functionality.
 */
@RunWith(JUnit4.class)
public class DirectRunnerTest implements Serializable {
  @Rule public transient ExpectedException thrown = ExpectedException.none();

  private Pipeline getPipeline() {
    PipelineOptions opts = PipelineOptionsFactory.create();
    opts.setRunner(DirectRunner.class);

    Pipeline p = Pipeline.create(opts);
    return p;
  }

  @Test
  public void defaultRunnerLoaded() {
    assertThat(DirectRunner.class,
        Matchers.<Class<? extends PipelineRunner>>equalTo(PipelineOptionsFactory.create()
            .getRunner()));
  }

  @Test
  public void wordCountShouldSucceed() throws Throwable {
    Pipeline p = getPipeline();

    PCollection<KV<String, Long>> counts =
        p.apply(Create.of("foo", "bar", "foo", "baz", "bar", "foo"))
            .apply(MapElements.via(new SimpleFunction<String, String>() {
              @Override
              public String apply(String input) {
                return input;
              }
            }))
            .apply(Count.<String>perElement());
    PCollection<String> countStrs =
        counts.apply(MapElements.via(new SimpleFunction<KV<String, Long>, String>() {
          @Override
          public String apply(KV<String, Long> input) {
            String str = String.format("%s: %s", input.getKey(), input.getValue());
            return str;
          }
        }));

    PAssert.that(countStrs).containsInAnyOrder("baz: 1", "bar: 2", "foo: 3");

    DirectPipelineResult result = ((DirectPipelineResult) p.run());
    result.awaitCompletion();
  }

  private static AtomicInteger changed;
  @Test
  public void reusePipelineSucceeds() throws Throwable {
    Pipeline p = getPipeline();

    changed = new AtomicInteger(0);
    PCollection<KV<String, Long>> counts =
        p.apply(Create.of("foo", "bar", "foo", "baz", "bar", "foo"))
            .apply(MapElements.via(new SimpleFunction<String, String>() {
              @Override
              public String apply(String input) {
                return input;
              }
            }))
            .apply(Count.<String>perElement());
    PCollection<String> countStrs =
        counts.apply(MapElements.via(new SimpleFunction<KV<String, Long>, String>() {
          @Override
          public String apply(KV<String, Long> input) {
            String str = String.format("%s: %s", input.getKey(), input.getValue());
            return str;
          }
        }));

    counts.apply(ParDo.of(new DoFn<KV<String, Long>, Void>() {
      @ProcessElement
      public void updateChanged(ProcessContext c) {
        changed.getAndIncrement();
      }
    }));


    PAssert.that(countStrs).containsInAnyOrder("baz: 1", "bar: 2", "foo: 3");

    DirectPipelineResult result = ((DirectPipelineResult) p.run());
    result.awaitCompletion();

    DirectPipelineResult otherResult = ((DirectPipelineResult) p.run());
    otherResult.awaitCompletion();

    assertThat("Each element should have been processed twice", changed.get(), equalTo(6));
  }

  @Test(timeout = 5000L)
  public void byteArrayCountShouldSucceed() {
    Pipeline p = getPipeline();

    SerializableFunction<Integer, byte[]> getBytes = new SerializableFunction<Integer, byte[]>() {
      @Override
      public byte[] apply(Integer input) {
        try {
          return CoderUtils.encodeToByteArray(VarIntCoder.of(), input);
        } catch (CoderException e) {
          fail("Unexpected Coder Exception " + e);
          throw new AssertionError("Unreachable");
        }
      }
    };
    TypeDescriptor<byte[]> td = new TypeDescriptor<byte[]>() {
    };
    PCollection<byte[]> foos =
        p.apply(Create.of(1, 1, 1, 2, 2, 3)).apply(MapElements.via(getBytes).withOutputType(td));
    PCollection<byte[]> msync =
        p.apply(Create.of(1, -2, -8, -16)).apply(MapElements.via(getBytes).withOutputType(td));
    PCollection<byte[]> bytes =
        PCollectionList.of(foos).and(msync).apply(Flatten.<byte[]>pCollections());
    PCollection<KV<byte[], Long>> counts = bytes.apply(Count.<byte[]>perElement());
    PCollection<KV<Integer, Long>> countsBackToString =
        counts.apply(MapElements.via(new SimpleFunction<KV<byte[], Long>, KV<Integer, Long>>() {
          @Override
          public KV<Integer, Long> apply(KV<byte[], Long> input) {
            try {
              return KV.of(CoderUtils.decodeFromByteArray(VarIntCoder.of(), input.getKey()),
                  input.getValue());
            } catch (CoderException e) {
              fail("Unexpected Coder Exception " + e);
              throw new AssertionError("Unreachable");
        }
      }
    }));

    Map<Integer, Long> expected = ImmutableMap.<Integer, Long>builder().put(1, 4L)
        .put(2, 2L)
        .put(3, 1L)
        .put(-2, 1L)
        .put(-8, 1L)
        .put(-16, 1L)
        .build();
    PAssert.thatMap(countsBackToString).isEqualTo(expected);
  }

  @Test
  public void transformDisplayDataExceptionShouldFail() {
    OldDoFn<Integer, Integer> brokenDoFn = new OldDoFn<Integer, Integer>() {
      @Override
      public void processElement(ProcessContext c) throws Exception {}

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        throw new RuntimeException("oh noes!");
      }
    };

    Pipeline p = getPipeline();
    p
        .apply(Create.of(1, 2, 3))
        .apply(ParDo.of(brokenDoFn));

    thrown.expectMessage(brokenDoFn.getClass().getName());
    thrown.expectCause(ThrowableMessageMatcher.hasMessage(is("oh noes!")));
    p.run();
  }

  @Test
  public void pipelineOptionsDisplayDataExceptionShouldFail() {
    Object brokenValueType = new Object() {
      @JsonValue
      public int getValue () {
        return 42;
      }

      @Override
      public String toString() {
        throw new RuntimeException("oh noes!!");
      }
    };

    Pipeline p = getPipeline();
    p.getOptions().as(ObjectPipelineOptions.class).setValue(brokenValueType);

    p.apply(Create.of(1, 2, 3));

    thrown.expectMessage(PipelineOptions.class.getName());
    thrown.expectCause(ThrowableMessageMatcher.hasMessage(is("oh noes!!")));
    p.run();
  }

  /** {@link PipelineOptions} to inject bad object implementations. */
  public interface ObjectPipelineOptions extends PipelineOptions {
    Object getValue();
    void setValue(Object value);
  }


  /**
   * Tests that a {@link OldDoFn} that mutates an output with a good equals() fails in the
   * {@link DirectRunner}.
   */
  @Test
  public void testMutatingOutputThenOutputDoFnError() throws Exception {
    Pipeline pipeline = getPipeline();

    pipeline
        .apply(Create.of(42))
        .apply(ParDo.of(new OldDoFn<Integer, List<Integer>>() {
          @Override public void processElement(ProcessContext c) {
            List<Integer> outputList = Arrays.asList(1, 2, 3, 4);
            c.output(outputList);
            outputList.set(0, 37);
            c.output(outputList);
          }
        }));

    thrown.expect(IllegalMutationException.class);
    thrown.expectMessage("output");
    thrown.expectMessage("must not be mutated");
    pipeline.run();
  }

  /**
   * Tests that a {@link OldDoFn} that mutates an output with a good equals() fails in the
   * {@link DirectRunner}.
   */
  @Test
  public void testMutatingOutputThenTerminateDoFnError() throws Exception {
    Pipeline pipeline = getPipeline();

    pipeline
        .apply(Create.of(42))
        .apply(ParDo.of(new OldDoFn<Integer, List<Integer>>() {
          @Override public void processElement(ProcessContext c) {
            List<Integer> outputList = Arrays.asList(1, 2, 3, 4);
            c.output(outputList);
            outputList.set(0, 37);
          }
        }));

    thrown.expect(IllegalMutationException.class);
    thrown.expectMessage("output");
    thrown.expectMessage("must not be mutated");
    pipeline.run();
  }

  /**
   * Tests that a {@link OldDoFn} that mutates an output with a bad equals() still fails
   * in the {@link DirectRunner}.
   */
  @Test
  public void testMutatingOutputCoderDoFnError() throws Exception {
    Pipeline pipeline = getPipeline();

    pipeline
        .apply(Create.of(42))
        .apply(ParDo.of(new OldDoFn<Integer, byte[]>() {
          @Override public void processElement(ProcessContext c) {
            byte[] outputArray = new byte[]{0x1, 0x2, 0x3};
            c.output(outputArray);
            outputArray[0] = 0xa;
            c.output(outputArray);
          }
        }));

    thrown.expect(IllegalMutationException.class);
    thrown.expectMessage("output");
    thrown.expectMessage("must not be mutated");
    pipeline.run();
  }

  /**
   * Tests that a {@link OldDoFn} that mutates its input with a good equals() fails in the
   * {@link DirectRunner}.
   */
  @Test
  public void testMutatingInputDoFnError() throws Exception {
    Pipeline pipeline = getPipeline();

    pipeline
        .apply(Create.of(Arrays.asList(1, 2, 3), Arrays.asList(4, 5, 6))
            .withCoder(ListCoder.of(VarIntCoder.of())))
        .apply(ParDo.of(new OldDoFn<List<Integer>, Integer>() {
          @Override public void processElement(ProcessContext c) {
            List<Integer> inputList = c.element();
            inputList.set(0, 37);
            c.output(12);
          }
        }));

    thrown.expect(IllegalMutationException.class);
    thrown.expectMessage("Input");
    thrown.expectMessage("must not be mutated");
    pipeline.run();
  }

  /**
   * Tests that a {@link OldDoFn} that mutates an input with a bad equals() still fails
   * in the {@link DirectRunner}.
   */
  @Test
  public void testMutatingInputCoderDoFnError() throws Exception {
    Pipeline pipeline = getPipeline();

    pipeline
        .apply(Create.of(new byte[]{0x1, 0x2, 0x3}, new byte[]{0x4, 0x5, 0x6}))
        .apply(ParDo.of(new OldDoFn<byte[], Integer>() {
          @Override public void processElement(ProcessContext c) {
            byte[] inputArray = c.element();
            inputArray[0] = 0xa;
            c.output(13);
          }
        }));

    thrown.expect(IllegalMutationException.class);
    thrown.expectMessage("Input");
    thrown.expectMessage("must not be mutated");
    pipeline.run();
  }
}
