/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.xdr.*;
import java.io.IOException;

public class nfsv4_1_file_layout4 implements XdrAble {
    public deviceid4 nfl_deviceid;
    public nfl_util4 nfl_util;
    public uint32_t nfl_first_stripe_index;
    public offset4 nfl_pattern_offset;
    public nfs_fh4 [] nfl_fh_list;

    public nfsv4_1_file_layout4() {
    }

    public nfsv4_1_file_layout4(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    @Override
    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        nfl_deviceid.xdrEncode(xdr);
        nfl_util.xdrEncode(xdr);
        nfl_first_stripe_index.xdrEncode(xdr);
        nfl_pattern_offset.xdrEncode(xdr);
        { int $size = nfl_fh_list.length; xdr.xdrEncodeInt($size); for ( int $idx = 0; $idx < $size; ++$idx ) { nfl_fh_list[$idx].xdrEncode(xdr); } }
    }

    @Override
    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        nfl_deviceid = new deviceid4(xdr);
        nfl_util = new nfl_util4(xdr);
        nfl_first_stripe_index = new uint32_t(xdr);
        nfl_pattern_offset = new offset4(xdr);
        { int $size = xdr.xdrDecodeInt(); nfl_fh_list = new nfs_fh4[$size]; for ( int $idx = 0; $idx < $size; ++$idx ) { nfl_fh_list[$idx] = new nfs_fh4(xdr); } }
    }

}
// End of nfsv4_1_file_layout4.java