/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.*;
import org.dcache.xdr.*;
import java.io.IOException;

public class CLOSE4res implements XdrAble {
    public int status;
    public stateid4 open_stateid;

    public CLOSE4res() {
    }

    public CLOSE4res(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(status);
        switch ( status ) {
        case nfsstat.NFS_OK:
            open_stateid.xdrEncode(xdr);
            break;
        default:
            break;
        }
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        status = xdr.xdrDecodeInt();
        switch ( status ) {
        case nfsstat.NFS_OK:
            open_stateid = new stateid4(xdr);
            break;
        default:
            break;
        }
    }

}
// End of CLOSE4res.java