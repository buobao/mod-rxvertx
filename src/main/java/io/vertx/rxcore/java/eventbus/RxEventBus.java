package io.vertx.rxcore.java.eventbus;

import io.vertx.rxcore.java.impl.HandlerSubscription;
import io.vertx.rxcore.java.impl.MemoizeHandler;
import io.vertx.rxcore.java.impl.SingleSubscriptionHandler;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import rx.Observable;
import rx.Subscriber;

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
public class RxEventBus {

  /** Default timeout used for all observe* operations */
  public final static int DEFAULT_TIMEOUT=60*1000;

  // Customer handlers
  
  /** Standard SendHandler */
  protected class SendHandler<R> extends MemoizeHandler<RxMessage<R>,Message<R>> {
    @Override public void handle(Message m) {
      complete(new RxMessageImpl(m));
    }
  }
  
  /** Async SendHandler */
  protected class AsyncSendHandler<R> extends SingleSubscriptionHandler<RxMessage<R>, AsyncResult<Message<R>>> {
    @Override public void handle(AsyncResult<Message<R>> r) {
      if (r.succeeded()) {
        fireResult(new RxMessageImpl(r.result()));
      }
      else {
        fireError(r.cause());
      }
    }
  }

  /** Async HandlerSubscription */
  protected class AsyncSendSubscription<R> extends HandlerSubscription<AsyncResult<Message<R>>,RxMessage<R>> {

    /** Create new AsyncSendSubscription */
    public AsyncSendSubscription(Subscriber<RxMessage<R>> s) {
      super(s);
    }

    /** Handle event */
    public void handle(AsyncResult<Message<R>> evt) {
      if (evt.succeeded()) {
        fireComplete(new RxMessageImpl(evt.result()));
      }
      else {
        fireError(evt.cause());
      }
    }
  }

  /** Receive handler */
  protected class ReceiveHandler<R> extends SingleSubscriptionHandler<RxMessage<R>,Message> {
    @Override public void handle(Message m) {
      fireNext(new RxMessageImpl(m));
    }
  }

  /** RxMessage implementation with inherited timeouts */
  protected class RxMessageImpl<R> extends RxMessage<R>
  {
    /** Create new RxMessageImpl */
    public RxMessageImpl(Message<R> coreMessage) {
      super(coreMessage);
    }

    /** Observe a reply */
    public <R,T> Observable<RxMessage<T>> observeReply(final R msg)
    {
      return Observable.create(new AsyncSendHandler<T>() {
        @Override public void execute() {
          coreMessage.replyWithTimeout(msg,defaultTimeout,this);
        }
      });
    }

    /** Observe a reply with timeout */
    public <R,T> Observable<RxMessage<T>> observeReplyWithTimeout(final R msg, final long timeout) {
      return Observable.create(new AsyncSendHandler<T>() {
        @Override public void execute() {
          coreMessage.replyWithTimeout(msg,timeout,this);
        }
      });
    }

  }

  // Instance variables

  /** Core bus */
  private final EventBus eventBus;

  /** Default timeout */
  protected final int defaultTimeout;

  // Public

  /** Create new RxEventBus */
  public RxEventBus(EventBus eventBus) {
    this(eventBus,DEFAULT_TIMEOUT);
  }

  /** Create new RxEventBus */
  public RxEventBus(EventBus eventBus, int defaultTimeout) {
    this.eventBus = eventBus;
    this.defaultTimeout=defaultTimeout;
  }

  /** Send a message */
  public <S,R> Observable<RxMessage<R>> send(final String address, final S msg) {
    SendHandler<R> h=new SendHandler<R>();
    this.eventBus.send(address,msg,(Handler)h);
    return Observable.create(h.subscribe);
  }
  
  /** Send a message with timeout */
  public <S,R> Observable<RxMessage<R>> sendWithTimeout(final String address, final S msg, final long timeout) {
    AsyncSendHandler<R> h=new AsyncSendHandler<R>();
    this.eventBus.sendWithTimeout(address,msg,timeout,h);
    return Observable.create(h); 
  }

  /** Create an Observable that executes send() on subscribe. Each new subscribe() will re-send the message */
  public <S,R> Observable<RxMessage<R>> observeSend(final String address, final S msg) {
    return Observable.create(new Observable.OnSubscribe<RxMessage<R>>() {
      /** Send message for each subscription */
      public void call(Subscriber<? super RxMessage<R>> subscriber) {
        AsyncSendSubscription hs=new AsyncSendSubscription(subscriber);
        eventBus.sendWithTimeout(address, (Object)msg, defaultTimeout, (Handler)hs);
        subscriber.add(hs);
      }
    });
  }
  
  /** Create an Observable that executes sendWithTimeout() on subscribe */
  public <S,R> Observable<RxMessage<R>> observeSendWithTimeout(final String address, final S msg, final long timeout) {
    return Observable.create(new Observable.OnSubscribe<RxMessage<R>>() {
      /** Send message for each subscription */
      public void call(Subscriber<? super RxMessage<R>> subscriber) {
        AsyncSendSubscription hs=new AsyncSendSubscription(subscriber);
        eventBus.sendWithTimeout(address, (Object)msg, timeout, hs);
        subscriber.add(hs);
      }
    });
  }

  /** Register a handler */
  public <T> Observable<RxMessage<T>> registerLocalHandler(final String address) {
    return Observable.create(new ReceiveHandler<T>() {
      @Override public void execute() {
        eventBus.registerLocalHandler(address,this);
      }
      @Override public void onUnsubscribed() {
        eventBus.unregisterHandler(address,this);
      }
    });
  }

  /** Register a handler */
  public <T> Observable<RxMessage<T>> registerHandler(final String address) {
    return Observable.create(new ReceiveHandler<T>() {
      @Override public void execute() {
        eventBus.registerHandler(address,this);
      }
      @Override public void onUnsubscribed() {
        eventBus.unregisterHandler(address,this);
      }
    });
  }

  /** Create an Observable that requests multiple messages in a sequence */
  public <S,R> Observable<RxStream<S,R>> observeStream(final String address, final S msg) {

    final RxStream<S,R> s=new RxStream<S,R>();

    return Observable.create(new SingleSubscriptionHandler<RxStream<S,R>,Message<R>>() {
      @Override public void execute() {
        s.callback=this;
        eventBus.send(address,msg,(Handler)this);
      }
      @Override public void handle(Message<R> msg) {
        // Change the current message and re-fire
        s.handle(msg);
        fireNext(s);
        // Check if there is any more to get
        if (msg.replyAddress()==null) {
          fireComplete();
        }
      }
    });
  }
}
