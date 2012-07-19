/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class device_addr4 implements XdrAble {
    public int da_layout_type;
    public byte [] da_addr_body;

    public device_addr4() {
    }

    public device_addr4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(da_layout_type);
        xdr.xdrEncodeDynamicOpaque(da_addr_body);
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        da_layout_type = xdr.xdrDecodeInt();
        da_addr_body = xdr.xdrDecodeDynamicOpaque();
    }

}
// End of device_addr4.java