package org.dcache.webdav;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;

import com.bradmcevoy.http.HttpManager;
import com.bradmcevoy.http.Resource;
import com.bradmcevoy.http.CollectionResource;
import com.bradmcevoy.http.PutableResource;
import com.bradmcevoy.http.GetableResource;
import com.bradmcevoy.http.DeletableResource;
import com.bradmcevoy.http.MakeCollectionableResource;
import com.bradmcevoy.http.LockingCollectionResource;
import com.bradmcevoy.http.Auth;
import com.bradmcevoy.http.Range;
import com.bradmcevoy.http.Request;
import com.bradmcevoy.http.LockInfo;
import com.bradmcevoy.http.LockTimeout;
import com.bradmcevoy.http.LockToken;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.BadRequestException;

import com.google.common.base.Objects;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.PermissionDeniedCacheException;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.FileExistsCacheException;

import org.dcache.vehicles.FileAttributes;
import org.jboss.netty.handler.codec.http.HttpHeaders;

/**
 * Exposes dCache directories as resources in the Milton WebDAV
 * framework.
 */
public class DcacheDirectoryResource
    extends DcacheResource
    implements PutableResource, GetableResource, DeletableResource,
               MakeCollectionableResource, LockingCollectionResource
{
    public DcacheDirectoryResource(DcacheResourceFactory factory,
                                   FsPath path, FileAttributes attributes)
    {
        super(factory, path, attributes);
    }

    @Override
    public String checkRedirect(Request request)
    {
        String url = request.getAbsoluteUrl();
        if (request.getMethod() == Request.Method.GET && !url.endsWith("/")) {
            return url + "/";
        }
        return null;
    }

    @Override
    public Resource child(String childName)
    {
        FsPath fchild = new FsPath(_path, childName);
        return _factory.getResource(fchild);
    }

    @Override
    public List<? extends Resource> getChildren()
    {
        try {
            return _factory.list(_path);
        } catch (FileNotFoundCacheException e) {
            return Collections.emptyList();
        } catch (PermissionDeniedCacheException e) {
            throw new UnauthorizedException(e.getMessage(), e, this);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, this);
        } catch (InterruptedException e) {
            throw new WebDavException(e.getMessage(), e, this);
        }
    }

    @Override
    public Resource createNew(String newName, InputStream inputStream,
                              Long length, String contentType)
        throws IOException, ConflictException, NotAuthorizedException,
               BadRequestException
    {
        try {
            FsPath path = new FsPath(_path, newName);
            if (_factory.shouldRedirect(HttpManager.request())) {
                throw new RedirectException(this, _factory.getWriteUrl(path, length));
            } else {
                return _factory.createFile(path, inputStream, length);
            }
        } catch (PermissionDeniedCacheException e) {
            throw new NotAuthorizedException(this);
        } catch (FileExistsCacheException e) {
            throw new ConflictException(this);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, this);
        } catch (InterruptedException e) {
            throw new WebDavException("Transfer was interrupted", e, this);
        } catch (URISyntaxException e) {
            throw new WebDavException("Invalid request URI: " + e.getMessage(), e, this);
        }
    }

    @Override
    public void sendContent(OutputStream out, Range range,
                            Map<String,String> params, String contentType)
        throws IOException, NotAuthorizedException
    {
        try {
            _factory.list(_path, new OutputStreamWriter(out, "UTF-8"));
        } catch (PermissionDeniedCacheException e) {
            throw new NotAuthorizedException(this);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, this);
        } catch (InterruptedException e) {
            throw new WebDavException(e.getMessage(), e, this);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This should not happen as UTF-8 " +
                    "is a required encoding for JVM", e);
        } catch (URISyntaxException e) {
            throw new WebDavException("Badly formed URI: " + e.getMessage(), this);
        }
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth)
    {
        return null;
    }

    @Override
    public String getContentType(String accepts)
    {
        return "text/html; charset=utf-8";
    }

    @Override
    public Long getContentLength()
    {
        return null;
    }

    @Override
    public void delete()
        throws NotAuthorizedException, ConflictException, BadRequestException
    {
        try {
            _factory.deleteDirectory(_attributes.getPnfsId(), _path);
        } catch (PermissionDeniedCacheException e) {
            throw new NotAuthorizedException(this);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, this);
        }
    }

    @Override
    public CollectionResource createCollection(String newName)
        throws NotAuthorizedException, ConflictException
    {
        try {
            return _factory.makeDirectory(_attributes,
                                          new FsPath(_path, newName));
        } catch (PermissionDeniedCacheException e) {
            throw new NotAuthorizedException(this);
        } catch (CacheException e) {
            throw new WebDavException(e.getMessage(), e, this);
        }
    }

    @Override
    public LockToken createAndLock(String name, LockTimeout timeout, LockInfo lockInfo)
    {
        /* We do not currently support createAndLock, but as Mac OS X
         * insists on lock support before it allows writing to a
         * WebDAV store, we return a lock with zero lifetime.
         *
         * We do not currently create the entry, as the normal action
         * after createAndLock is to perform a PUT which immediately
         * overwrites the empty site.
         */
        return createNullLock();
    }
}