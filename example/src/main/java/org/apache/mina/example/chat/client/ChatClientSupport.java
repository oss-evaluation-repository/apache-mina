/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.mina.example.chat.client;

import java.io.IOException;
import java.net.SocketAddress;

import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.IoHandler;
import org.apache.mina.common.IoSession;
import org.apache.mina.transport.socket.nio.SocketConnector;

/**
 * A simple chat client for a given user.
 * 
 * @author The Apache Directory Project (mina-dev@directory.apache.org)
 * @version $Rev$, $Date$
 */
public class ChatClientSupport
{
    private final IoHandler handler;
    private final String name;
    private IoSession session;

    public ChatClientSupport( String name, IoHandler handler )
    {
        if( name == null )
        {
            throw new IllegalArgumentException( "Name can not be null" );
        }
        this.name = name;
        this.handler = handler;
    }

    public void connect( SocketConnector connector, SocketAddress address )
            throws IOException
    {
        if( session != null && session.isConnected() )
        {
            throw new IllegalStateException( "Already connected. Disconnect first." );
        }
        
        ConnectFuture future1 = connector.connect( address, handler );
        future1.join();
        session = future1.getSession();
    }

    public void login()
    {
        session.write( "LOGIN " + name );
    }

    public void broadcast( String message )
    {
        session.write( "BROADCAST " + message );
    }

    public void quit()
    {
        if( session != null )
        {
            if( session.isConnected() )
            {
                session.write( "QUIT" );
                // Wait until the chat ends.
                session.getCloseFuture().join();
            }
            session.close();
        }
    }

}