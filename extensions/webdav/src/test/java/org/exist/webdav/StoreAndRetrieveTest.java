/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.exist.webdav;

import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.exceptions.NotFoundException;
import com.ettrema.httpclient.*;
import org.apache.http.impl.client.AbstractHttpClient;
import org.exist.TestUtils;
import org.exist.test.ExistWebServer;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Tests for storing and retrieving a document via WebDAV.
 */
public class StoreAndRetrieveTest {

    @ClassRule
    public static final ExistWebServer existWebServer = new ExistWebServer(true, false, true, true);

    @ClassRule
    public static final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void storeAndRetrieveXmlDocument() throws IOException, NotAuthorizedException, BadRequestException, HttpException, ConflictException, NotFoundException {
        final String srcDocName = "webdav-store-and-retrieve-test.xml";
        final String srcDocContent = "<elem1>Hello there</elem1>";
        storeAndRetrieve(srcDocName, srcDocContent, "application/xml");
    }

    @Test
    public void storeAndRetrieveBinDocument() throws IOException, NotAuthorizedException, BadRequestException, HttpException, ConflictException, NotFoundException {
        final String srcDocName = "webdav-store-and-retrieve-test.bin";
        final String srcDocContent = "0123456789";
        storeAndRetrieve(srcDocName, srcDocContent, "application/octet-stream");
    }

    private void storeAndRetrieve(final String srcDocName, final String srcDocContent, final String expectedMediaType) throws BadRequestException, HttpException, IOException, NotAuthorizedException, ConflictException, NotFoundException {
        final HostBuilder builder = new HostBuilder();
        builder.setServer("localhost");
        final int port = existWebServer.getPort();
        builder.setPort(port);
        builder.setRootPath("webdav/db");
        final Host host = builder.buildHost();

        // workaround pre-emptive auth issues of Milton Client
        final AbstractHttpClient httpClient = (AbstractHttpClient)host.getClient();
        httpClient.addRequestInterceptor(new AlwaysBasicPreAuth(TestUtils.ADMIN_DB_USER, TestUtils.ADMIN_DB_PWD));

        final Folder folder = host.getFolder("/");
        assertNotNull(folder);

        // store document
        final byte data[] = srcDocContent.getBytes(UTF_8);
        final java.io.File tmpStoreFile = tempFolder.newFile();
        Files.write(tmpStoreFile.toPath(), data);
        assertNotNull(folder.uploadFile(srcDocName, tmpStoreFile, null));

        // retrieve document
        final Resource srcResource = folder.child(srcDocName);
        assertNotNull(srcResource);
        assertTrue(srcResource instanceof File);
        assertEquals(expectedMediaType, ((File) srcResource).contentType);
        final java.io.File tempRetrievedSrcFile = tempFolder.newFile();
        srcResource.downloadTo(tempRetrievedSrcFile, null);
        assertEquals(srcDocContent, new String(Files.readAllBytes(tempRetrievedSrcFile.toPath()), UTF_8));
    }
}
