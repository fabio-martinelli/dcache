/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v3.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class set_mode3 implements XdrAble {
    public boolean set_it;
    public mode3 mode;

    public set_mode3() {
    }

    public set_mode3(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeBoolean(set_it);
        if ( set_it ) {
            mode.xdrEncode(xdr);
        }
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        set_it = xdr.xdrDecodeBoolean();
        if ( set_it ) {
            mode = new mode3(xdr);
        }
    }

}
// End of set_mode3.java