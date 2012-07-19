// $Id: MoverInfoMessage.java,v 1.5 2006-04-11 09:47:53 tigran Exp $
package diskCacheV111.vehicles ;
import  diskCacheV111.util.PnfsId ;
import org.stringtemplate.v4.ST;

public class MoverInfoMessage extends PnfsFileInfoMessage {

   private long    _dataTransferred = 0 ;
   private long    _connectionTime  = 0 ;

   private ProtocolInfo _protocolInfo = null ;
   private boolean      _fileCreated  = false ;
   private String _initiator = "<undefined>";
   private String _client = "unknown";

   private static final long serialVersionUID = -7013160118909496211L;

   public MoverInfoMessage( String cellName ,
                            PnfsId pnfsId     ){

      super( "transfer" , "pool" , cellName , pnfsId ) ;
   }
   public void setFileCreated( boolean created ){ _fileCreated = created ; }
   public void setTransferAttributes(
                   long dataTransferred ,
                   long connectionTime ,
                   ProtocolInfo protocolInfo  ){
      _dataTransferred = dataTransferred ;
      _connectionTime  = connectionTime ;
      _protocolInfo    = protocolInfo ;
   }
   public void setInitiator(String transaction) {
       _initiator = transaction;
   }
   public String getInitiator() {
       return _initiator;
   }

   public long getDataTransferred(){ return _dataTransferred ; }
   public long getConnectionTime(){ return _connectionTime ; }
   public boolean isFileCreated(){ return _fileCreated ; }
   public ProtocolInfo getProtocolInfo(){ return _protocolInfo ; }

    public String getAdditionalInfo() {
       return _dataTransferred + " "
                + _connectionTime + " "
                + _fileCreated + " {"
                + _protocolInfo + "} ["
                + _initiator + "] ";
}
   public String toString(){
      return getInfoHeader()+" "+
             getFileInfo()+" "+
             getAdditionalInfo() +
             getResult() ;
   }

    @Override
    public void fillTemplate(ST template)
    {
        super.fillTemplate(template);
        template.add("transferred", _dataTransferred);
        template.add("connectionTime", _connectionTime);
        template.add("created", _fileCreated);
        template.add("protocol", _protocolInfo);
        template.add("initiator", _initiator);
    }
}