/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import java.util.Objects;
import java.util.function.Consumer;

import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;

/**
 * @author Stephane Maldini
 */
final class HttpServerSecure extends HttpServerOperator
		implements Consumer<SslProvider.SslContextSpec> {

	final Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder;

	HttpServerSecure(HttpServer server,
			Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		super(server);
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		this.sslProviderBuilder = sslProviderBuilder;
	}

	@Override
	public void accept(SslProvider.SslContextSpec spec) {
		if (spec instanceof SslProvider.DefaultConfigurationSpec) {
			((SslProvider.DefaultConfigurationSpec)spec).defaultConfiguration(SslProvider.DefaultConfigurationType.HTTP);
		}
		sslProviderBuilder.accept(spec);
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return source.tcpConfiguration().secure(this);
	}
}
