package io.vertx.rxcore.test.integration.java;

/*
 * Copyright 2013 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.rxcore.java.eventbus.RxEventBus;
import io.vertx.rxcore.java.eventbus.RxMessage;
import io.vertx.rxcore.java.eventbus.RxStream;
import io.vertx.rxcore.java.impl.Regulator;
import org.junit.Test;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.eventbus.ReplyException;
import org.vertx.java.core.json.JsonArray;
import org.vertx.testtools.TestVerticle;
import rx.Observable;
import rx.Subscription;
import rx.functions.*;

import static io.vertx.rxcore.test.integration.java.RxAssert.*;
import static org.vertx.testtools.VertxAssert.assertEquals;
import static org.vertx.testtools.VertxAssert.fail;
import static org.vertx.testtools.VertxAssert.testComplete;

public class EventBusIntegrationTest extends TestVerticle {

  @Test
  public void testClose() {

    RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        message.reply("pong!");
      }
    });
    Observable<RxMessage<String>> obs = rxEventBus.send("foo", "ping!");
    obs.subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        assertEquals("pong!", message.body());
      }
    });

    assertCompletes(rxEventBus.close());
  }

  @Test
  public void testPublish() {

    RxEventBus rxEventBus=new RxEventBus(vertx.eventBus());

    final CountDownLatch received=new CountDownLatch(2);

    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      public void call(RxMessage<String> message) {
        assertEquals("shout!", message.body());
        received.countDown();
      }
    });
    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      public void call(RxMessage<String> message) {
        assertEquals("shout!", message.body());
        received.countDown();
      }
    });

    rxEventBus.publish("foo","shout!");

    RxAssert.assertCompleted(received);
  }

  @Test
  public void testSimpleSubscribeReply() {
    RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());
    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        message.reply("pong!");
      }
    });
    Observable<RxMessage<String>> obs = rxEventBus.send("foo", "ping!");
    obs.subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        assertEquals("pong!", message.body());
        testComplete();
      }
    });
  }

  @Test
  public void testDeferredSubscribe() {
    RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());
    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        message.reply("pong!");
      }
    });
    // Message is sent on send
    final Observable<RxMessage<String>> obs = rxEventBus.send("foo", "ping!");
    // Defer the subscribe
    vertx.setTimer(100,new Handler<Long>() {
      public void handle(Long ev) {
        obs.subscribe(new Action1<RxMessage<String>>() {
          @Override
          public void call(RxMessage<String> message) {
            assertEquals("pong!", message.body());
            testComplete();
          }
        });
      }
    });
  }

  @Test
  // Send some messages in series - i.e. wait for result of previous one before sending next one
  // PMCD: Added check to enforce 1-at-a-time 
  //       
  public void testSimpleSerial() {
    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());
    final AtomicInteger totalReqs = new AtomicInteger(3);
    final AtomicInteger activeReqs = new AtomicInteger(0);
    
    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        System.out.println("serial-foo["+message.body()+"]");
        message.reply("pong!");
        activeReqs.incrementAndGet();
      }
    });

    Observable<RxMessage<String>> obs1 = rxEventBus.observeSend("foo", "ping!");
    Observable<RxMessage<String>> obs2 = rxEventBus.observeSend("foo", "ping!");
    Observable<RxMessage<String>> obs3 = rxEventBus.observeSend("foo", "ping!");

    Observable<RxMessage<String>> concatenated = Observable.concat(obs1, obs2, obs3);

    concatenated.subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        System.out.println("serial-resp["+message.body()+"]");
        assertEquals("pong!", message.body());
        assertEquals(0,activeReqs.decrementAndGet());
        if (totalReqs.decrementAndGet()==0)
          testComplete();
      }
    });
  }

  @Test
  // Send some messages in series where next message sent is function of reply from previous message
  public void testSerial() {
    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> message) {
        message.reply(message.body());
      }
    });

    Observable<RxMessage<String>> obs1 = rxEventBus.send("foo", "ping1");
    Observable<RxMessage<String>> obs2 = obs1.flatMap(new Func1<RxMessage<String>, Observable<RxMessage<String>>>() {
      @Override
      public Observable<RxMessage<String>> call(RxMessage<String> reply) {
        return rxEventBus.send("foo", reply.body() + "ping2");
      }
    });
    Observable<RxMessage<String>> obs3 = obs2.flatMap(new Func1<RxMessage<String>, Observable<RxMessage<String>>>() {
      @Override
      public Observable<RxMessage<String>> call(RxMessage<String> reply) {
        return rxEventBus.send("foo", reply.body() + "ping3");
      }
    });
    
    assertMessageThenComplete(obs3,"ping1ping2ping3");
  }

  @Test
  // Send some messages in parallel and wait for all replies before doing something
  public void testGather() {

    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> msg) {
        msg.reply("pong"+msg.body());
      }
    });

    Observable<RxMessage<String>> obs1 = rxEventBus.send("foo", "A");
    Observable<RxMessage<String>> obs2 = rxEventBus.send("foo", "B");
    Observable<RxMessage<String>> obs3 = rxEventBus.send("foo", "C");
    Observable<RxMessage<String>> merged = Observable.merge(obs1, obs2, obs3);

    assertMessageThenComplete(merged.takeLast(1),"pongC");
  }

  @Test
  // Send some messages in parallel and return result of concatenating all the messages
  public void testConcatResults() {
    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    rxEventBus.<String>registerHandler("foo").subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> msg) {
        msg.reply("pong"+msg.body());
      }
    });

    Observable<RxMessage<String>> obs1 = rxEventBus.send("foo", "A");
    Observable<RxMessage<String>> obs2 = rxEventBus.send("foo", "B");
    Observable<RxMessage<String>> obs3 = rxEventBus.send("foo", "C");
    Observable<RxMessage<String>> merged = Observable.merge(obs1, obs2, obs3);
    Observable<String> result = merged.reduce("", new Func2<String, RxMessage<String>, String>() {
      @Override
      public String call(String accum, RxMessage<String> reply) {
        return accum + reply.body();
      }
    });

    RxAssert.assertSequenceThenComplete(result.takeLast(1),"pongApongBpongC");
  }

  @Test
  public void testSimpleRegisterHandler() {
    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    Observable<RxMessage<String>> obs = rxEventBus.registerHandler("foo");

    assertMessageThenComplete(obs.take(1),"hello");

    // Send using core EventBus
    vertx.eventBus().send("foo", "hello");
  }

  @Test
  public void testUnsubscribeUnregister() {

    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    Action0 doNothing = new Action0() {
      public void call() {}
    };

    Observable<RxMessage<String>> obs = rxEventBus.registerHandler("foo");

    // Prove no-one listening until subscribed
    assertErrorThen(rxEventBus.observeSend("foo", "no"), doNothing, ReplyException.class);

    // Register a handler
    final Subscription sub = obs.subscribe(new Action1<RxMessage<String>>() {
      public void call(RxMessage<String> msg) {
        System.out.println("received:"+msg);
        assertEquals("yes",msg.body());
        msg.reply("pong");
      }
    });

    // Send one message then trigger unsubscribe
    rxEventBus.observeSend("foo", "yes").subscribe(new Action1<RxMessage<Object>>() {
      public void call(RxMessage<Object> msg) {
        System.out.println("received-response:"+msg);
        assertEquals("pong",msg.body());
        sub.unsubscribe();
        // Prove no-one listening again
        assertErrorThenComplete(rxEventBus.observeSend("foo","never"), ReplyException.class);
      }
    });
  }

  @Test
  public void testReplyToReply() {
    final RxEventBus rxEventBus = new RxEventBus(vertx.eventBus());

    Observable<RxMessage<String>> obsReply1 = rxEventBus.registerHandler("foo");

    Observable<RxMessage<String>> obsReply2 = obsReply1.flatMap(new Func1<RxMessage<String>, Observable<RxMessage<String>>>() {
      @Override
      public Observable<RxMessage<String>> call(RxMessage<String> stringRxMessage) {
        // Reply to the message
        assertEquals("hello1", stringRxMessage.body());
        return stringRxMessage.observeReply("goodday1");
      }
    });

    Observable<RxMessage<String>> obsReply3 = obsReply2.flatMap(new Func1<RxMessage<String>, Observable<RxMessage<String>>>() {
      @Override
      public Observable<RxMessage<String>> call(RxMessage<String> stringRxMessage) {
        // Reply to the reply!
        assertEquals("hello2", stringRxMessage.body());
        return stringRxMessage.observeReply("goodday2");
      }
    });
    obsReply3.subscribe(new Action1<RxMessage<String>>() {
      @Override
      public void call(RxMessage<String> stringRxMessage) {
      }
    });

    Observable<RxMessage<String>> obsSend1 = rxEventBus.send("foo", "hello1");

    Observable<RxMessage<String>> obsSend2 = obsSend1.flatMap(new Func1<RxMessage<String>, Observable<RxMessage<String>>>() {
      @Override
      public Observable<RxMessage<String>> call(RxMessage<String> stringRxMessage) {
        // The first reply
        assertEquals("goodday1", stringRxMessage.body());
        // Now reply to the reply
        return stringRxMessage.observeReply("hello2");
      }
    });

    assertMessageThenComplete(obsSend2,"goodday2");
  }

  @Test
  public void testTimeout() {
    final RxEventBus rx=new RxEventBus(vertx.eventBus());

    // Register handler that timesout
    rx.<String>registerHandler("thewall").subscribe(new Action1<RxMessage<String>>() {
      public void call(RxMessage<String> req) {
        // No-one listens
        assertErrorThenComplete(req.observeReplyWithTimeout("pong", 200), ReplyException.class);
      }
    });

    rx.send("thewall","ping");
  }

  @Test
  public void testRetry() {

    final RxEventBus rx=new RxEventBus(vertx.eventBus());

    vertx.eventBus().registerHandler("every3", new Handler<Message<Integer>>() {

      private int times=0;

      public void handle(Message<Integer> msg) {
        if (times++%3!=2) {
          System.out.println("no!");
          msg.fail(500,"no");
          return;
        }
        System.out.println("yes!");
        msg.reply("yes");
      }
    });

    // Keep asking
    Observable<String> res=rx.<String,String>observeSend("every3", "please")
      .map(new Func1<RxMessage<String>, String>() {
        public String call(RxMessage<String> msg) {
          return msg.body();
        }
      })
      .retry(3);

    assertCountThenComplete(res, 1);
  }

  @Test
  public void testStream() {

    final RxEventBus rx=new RxEventBus(vertx.eventBus());

    vertx.eventBus().registerHandler("countdown", new Handler<Message<Integer>>() {

      public void sendBatch(final Message<Integer> msg, int from, int length) {
        JsonArray res = new JsonArray();
        for (int i = from; i < from+length; i++) {
          res.add(i);
        }
        // As long as above 0 wait for another request
        if (from > 0) {
          msg.reply(res, this);
        } else {
          msg.reply(res);
        }
      }

      public void handle(Message<Integer> msg) {
        sendBatch(msg, msg.body(), 10);
      }
    });

    Observable<Buffer> res=rx.<Integer,JsonArray>observeStream("countdown", 400)
      .map(new Func1<RxStream<Integer, JsonArray>, JsonArray>() {
        public JsonArray call(RxStream<Integer, JsonArray> s) {
          int start = s.value().get(0);
          if (start > 0) {
            s.next(start - 10);
          }
          return s.value();
        }
      })
      .map(new Func1<JsonArray, Buffer>() {
        public Buffer call(JsonArray data) {
          return new Buffer(data.encode());
        }
      });

    assertCountThenComplete(res,41);
  }

  @Test
  public void testFlow() {

    final RxEventBus rx=new RxEventBus(vertx.eventBus());

    vertx.eventBus().registerHandler("countdown", new Handler<Message<Integer>>() {

      public void sendBatch(final Message<Integer> msg, int from, int length) {
        JsonArray res = new JsonArray();
        for (int i = from; i < from+length; i++) {
          res.add(i);
        }
        // As long as above 0 wait for another request
        if (from > 0) {
          msg.reply(res, this);
        } else {
          msg.reply(res);
        }
      }

      public void handle(Message<Integer> msg) {
        sendBatch(msg, msg.body(), 10);
      }
    });

    Regulator regulator=new Regulator<>();

    Observable<RxStream<Integer,JsonArray>> res=rx.<Integer,JsonArray>observeStream("countdown", 4000)
      // Add the regulator gate here
      .lift(regulator)
      // Process the stream
      .map(new Func1<RxStream<Integer, JsonArray>, JsonArray>() {
        public JsonArray call(RxStream<Integer, JsonArray> s) {
          int start = s.value().get(0);
          if (start > 0) {
            s.next(start - 10);
          }
          return s.value();
        }
      })
      .map(new Func1<JsonArray, Buffer>() {
        public Buffer call(JsonArray data) {
          return new Buffer(data.encode());
        }
      });

    // Create a WriteStream that can only handle 5 writes per second
    RatedWriteStream out=new RatedWriteStream(vertx,5);

    assertCountThenComplete(regulator.stream(res,out),401);
  }

  @Test
  public void testFail() {
    final RxEventBus rxEventBus=new RxEventBus(vertx.eventBus());

    rxEventBus.<String>registerHandler("fail").subscribe(new Action1<RxMessage<String>>() {
      public void call(RxMessage<String> req) {
        req.fail(-1, "oops");
      }
    });

    assertErrorThenComplete(rxEventBus.observeSend("fail", "ping"), ReplyException.class, "oops");
  }
}
