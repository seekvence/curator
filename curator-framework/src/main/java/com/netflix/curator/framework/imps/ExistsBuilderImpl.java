/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.curator.framework.imps;

import com.netflix.curator.RetryLoop;
import com.netflix.curator.TimeTrace;
import com.netflix.curator.framework.api.BackgroundCallback;
import com.netflix.curator.framework.api.BackgroundPathable;
import com.netflix.curator.framework.api.CuratorEvent;
import com.netflix.curator.framework.api.CuratorEventType;
import com.netflix.curator.framework.api.ExistsBuilder;
import com.netflix.curator.framework.api.Pathable;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

class ExistsBuilderImpl implements ExistsBuilder, BackgroundOperation<String>
{
    private final CuratorFrameworkImpl client;
    private Backgrounding backgrounding;
    private Watching watching;

    ExistsBuilderImpl(CuratorFrameworkImpl client)
    {
        this.client = client;
        backgrounding = new Backgrounding();
        watching = new Watching();
    }

    @Override
    public BackgroundPathable<Stat> watched()
    {
        watching = new Watching(true);
        return this;
    }

    @Override
    public BackgroundPathable<Stat> usingWatcher(Watcher watcher)
    {
        watching = new Watching(watcher);
        return this;
    }

    @Override
    public Pathable<Stat> inBackground(BackgroundCallback callback)
    {
        backgrounding = new Backgrounding(callback);
        return this;
    }

    @Override
    public Pathable<Stat> inBackground(BackgroundCallback callback, Executor executor)
    {
        backgrounding = new Backgrounding(client, callback, executor);
        return this;
    }

    @Override
    public Pathable<Stat> inBackground()
    {
        backgrounding = new Backgrounding(true);
        return this;
    }

    @Override
    public Pathable<Stat> inBackground(Object context)
    {
        backgrounding = new Backgrounding(context);
        return this;
    }

    @Override
    public void performBackgroundOperation(final OperationAndData<String> operationAndData) throws Exception
    {
        final TimeTrace   trace = client.getZookeeperClient().startTracer("ExistsBuilderImpl-Background");
        AsyncCallback.StatCallback callback = new AsyncCallback.StatCallback()
        {
            @Override
            public void processResult(int rc, String path, Object ctx, Stat stat)
            {
                trace.commit();
                CuratorEvent event = new CuratorEventImpl(CuratorEventType.EXISTS, rc, path, null, ctx, stat, null, null, null, null);
                client.processBackgroundOperation(operationAndData, event);
            }
        };
        if ( watching.isWatched() )
        {
            client.getZooKeeper().exists(operationAndData.getData(), true, callback, backgrounding.getContext());
        }
        else
        {
            client.getZooKeeper().exists(operationAndData.getData(), watching.getWatcher(), callback, backgrounding.getContext());
        }
    }

    @Override
    public Stat forPath(String path) throws Exception
    {
        path = client.fixForNamespace(path);

        Stat        returnStat = null;
        if ( backgrounding.inBackground() )
        {
            client.processBackgroundOperation(new OperationAndData<String>(this, path, backgrounding.getCallback()), null);
        }
        else
        {
            returnStat = pathInForeground(path);
        }

        return returnStat;
    }

    private Stat pathInForeground(final String path) throws Exception
    {
        TimeTrace   trace = client.getZookeeperClient().startTracer("ExistsBuilderImpl-Foreground");
        Stat        returnStat = RetryLoop.callWithRetry
        (
            client.getZookeeperClient(),
            new Callable<Stat>()
            {
                @Override
                public Stat call() throws Exception
                {
                    Stat    returnStat;
                    if ( watching.isWatched()  )
                    {
                        returnStat = client.getZooKeeper().exists(path, true);
                    }
                    else
                    {
                        returnStat = client.getZooKeeper().exists(path, watching.getWatcher());
                    }
                    return returnStat;
                }
            }
        );
        trace.commit();
        return returnStat;
    }
}
