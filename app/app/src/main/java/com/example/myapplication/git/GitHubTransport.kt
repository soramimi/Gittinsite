package com.example.myapplication.git

import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.security.GeneralSecurityException

internal fun githubTransport(url: String, token: String, allowAnonymous: Boolean = false): TransportConfigCallback {
    requireGit(allowAnonymous || token.isNotBlank(), "GitHub PAT を入力してください。")
    val endpoint = URL(url)
    return TransportConfigCallback { transport ->
        val http = transport as? TransportHttp
            ?: throw GitOperationException("GitHub HTTPS 接続を開けませんでした。")
        requireGit(transport.uri.toString() == url, "登録した GitHub の URL と接続先が一致しません。")
        http.setHttpConnectionFactory(GitHubConnectionFactory(endpoint))
        if (token.isNotBlank()) http.setPreemptiveBasicAuthentication("x-access-token", token)
    }
}

private class GitHubConnectionFactory(private val endpoint: URL) : HttpConnectionFactory2 {
    private val delegate = JDKHttpConnectionFactory()
    private val paths = setOf("info/refs", "git-upload-pack", "git-receive-pack")
        .map { "${endpoint.path}/$it" }.toSet()

    override fun create(url: URL): HttpConnection = create(url, Proxy.NO_PROXY)

    override fun create(url: URL, proxy: Proxy?): HttpConnection {
        if (url.protocol != "https" || url.host != endpoint.host || url.port != endpoint.port ||
            url.userInfo != null || url.path !in paths
        ) {
            throw IOException("登録した GitHub リポジトリ以外への接続を拒否しました。")
        }
        return delegate.create(url, proxy)
    }

    override fun newSession(): HttpConnectionFactory2.GitSession {
        val session = delegate.newSession()
        return object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection, sslVerify: Boolean): HttpConnection {
                if (!sslVerify) throw GeneralSecurityException("TLS 証明書の検証が必要です。")
                return session.configure(connection, true)
            }

            override fun close() = session.close()
        }
    }
}
