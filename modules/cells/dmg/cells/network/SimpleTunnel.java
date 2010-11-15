package dmg.cells.network ;

import  dmg.cells.nucleus.* ;
import  dmg.util.Args ;
import  java.util.Date ;
import  java.io.* ;
import  java.net.* ;

/**
  *  
  *
  * @author Patrick Fuhrmann
  * @version 0.1, 15 Feb 1998
  */
public class SimpleTunnel implements Cell, Runnable, CellTunnel {
 
   private CellNucleus  _nucleus         = null ;
   private Thread       _senderThread    = null ;
   private Thread       _receiverThread  = null ;
   private Thread       _connectorThread = null ;
   private ObjectInputStream  _input     = null ;
   private ObjectOutputStream _output    = null ;
   private Socket       _socket ;
   private String       _state           = "Not Initialized" ;
   private String       _mode            = "None" ;
   private CellRoute       _route        = null ;
   private boolean         _ready        = false ;
   private Object          _readyLock    = new Object() ;
   private CellDomainInfo  _remoteDomainInfo = null ;
   
   public SimpleTunnel( String cellName , String argString )
          throws Exception {
          
      Args args = new Args( argString ) ;
      if( args.argc() < 2 )
           throw new IllegalArgumentException( "Wrong Usage" ) ;
      
      
      _SimpleTunnel( cellName , 
                     args.argv(0) ,
                     new Integer( args.argv(1) ).intValue() ) ;
   }
   public SimpleTunnel( String cellName , String host , int port )
          throws Exception {
      _SimpleTunnel( cellName , host , port ) ;
   }
   private void _SimpleTunnel( String cellName , String host , int port )
          throws Exception {
          
      InetAddress address = InetAddress.getByName( host ) ;
      
      _socket  = new Socket( address , port ) ;
      _mode    = "Connection" ;
      _nucleus = new CellNucleus( this , cellName ) ;

      _connectorThread = _nucleus.newThread( this , "Connector" ) ;
      _connectorThread.start() ;
      
   
   }
   public SimpleTunnel( String cellName , Socket socket ){
   
      _mode    = "Acception" ;
      _nucleus = new CellNucleus( this , cellName ) ;
      
      
      _socket  = socket ;

      _connectorThread = _nucleus.newThread( this , "Connector" ) ;
      _connectorThread.start() ;
      

      
   }
   public CellTunnelInfo getCellTunnelInfo(){
      return new CellTunnelInfo( _nucleus.getCellName() ,
                                 _nucleus.getCellDomainInfo() ,
                                 _remoteDomainInfo ) ;
   
   }
   private void _connector() throws Exception {
       _output.writeObject( _nucleus.getCellDomainInfo() ) ;
       Object obj = _input.readObject() ;
       if( obj == null )
         throw new IOException( "Premature EOS encountered" ) ;
       _remoteDomainInfo = (CellDomainInfo) obj ;
   }
   private void _acceptor() throws Exception {
       _output.writeObject( _nucleus.getCellDomainInfo() ) ;
       Object obj = _input.readObject() ;
       if( obj == null )
         throw new IOException( "Premature EOS encountered" ) ;
       _remoteDomainInfo = (CellDomainInfo) obj ;
    }
   private void _makeStreams() throws IOException {
      _output  = new ObjectOutputStream( _socket.getOutputStream() ) ;
      if( _output == null ){
          throw new IOException( "OutputStream == null" ) ;
      }
      _input   = new ObjectInputStream(  _socket.getInputStream() ) ;
      if( _input == null ){
          _output.close() ;
          throw new IOException( "InputStream == null" ) ;
      }
   }
   public void run(){
      if( Thread.currentThread() == _connectorThread ){
        _state = "Initializing" ;
         try{
           _nucleus.say( "Creating Streams in "+_mode+" Mode" ) ;
           _makeStreams()  ;
           _nucleus.say( "Streams created" ) ;
           _nucleus.say( "Running "+_mode+" Protocol" ) ;
           if( _mode.equals("Acception" ))_acceptor() ;
           else                           _connector() ;
           _nucleus.say( "Protocol ready ("+_remoteDomainInfo+")" ) ;
           
         }catch( Exception nse ){
           _nucleus.say( " Problem in Initial Protocol : "+nse ) ;
           try{_socket.close() ;}catch(Exception ee){} ;
           _nucleus.kill() ;
           return ;
         }
         _nucleus.say( "Starting I/O threads " ) ;
         _receiverThread = _nucleus.newThread( this , "Receiver" ) ;
         _receiverThread.start() ;
         _senderThread = _nucleus.newThread( this , "Sender" ) ;
         _senderThread.start() ;
         
         _route = new CellRoute( _remoteDomainInfo.getCellDomainName() , 
                                 _nucleus.getCellName() ,
                                 CellRoute.DOMAIN ) ;
         _nucleus.say( "Route added : "+_route );
         _nucleus.routeAdd( _route ) ; 
        _state = "Active" ;
        
      }else if( Thread.currentThread() == _receiverThread ){
        try{
           Object obj ;
           while( ( obj = _input.readObject() ) != null ){
              CellMessage msg = (CellMessage) obj ;
              _nucleus.say( " Message from tunnel : "+msg ) ;
              try{  
                 _nucleus.sendMessage( msg ) ;
              }catch( NoRouteToCellException nrtce ){
                 _nucleus.say( "Exception while resending message : "+nrtce ) ;
              }
           }
        }catch( Exception ioe ){
           _nucleus.say( "Exception while receiving message : "+ioe ) ;
           _nucleus.kill() ;
        }
      }else if( Thread.currentThread() == _senderThread ){
      }
   }
   public String toString(){
      if( _remoteDomainInfo == null )return "M="+_mode+";S="+_state ;
      else return "M="+_mode+";S="+_state+";P="+_remoteDomainInfo.getCellDomainName() ;
   }
   public String getInfo(){
     StringBuffer sb = new StringBuffer() ;
     sb.append( "Simple Tunnel : "+_nucleus.getCellName()+"\n" ) ;
     sb.append( "Mode          : "+_mode+"\n" ) ;
     sb.append( "Status        : "+_state+"\n" ) ;
     if( _remoteDomainInfo == null )
        sb.append( "Peer          : N.N.\n" ) ;
     else 
        sb.append( "Peer          : "+_remoteDomainInfo.getCellDomainName()+"\n" ) ;
     
     return sb.toString() ;
   }
   public void   messageArrived( MessageEvent me ){
//     _nucleus.say( "message Arrived : "+me ) ;
     if( me instanceof RoutedMessageEvent ){
       try{
       
          CellMessage msg = me.getMessage() ;
          _nucleus.say( "Message tunneling : "+msg ) ;
          _output.writeObject( msg ) ;
          _output.flush();
          
       
       }catch( Exception ioe ){
          _nucleus.say( "Exception while sending message : "+ioe ) ;
       }
     }else if( me instanceof LastMessageEvent ){
        _nucleus.say( "Got last message ; releasing lock " ) ;
        synchronized( _readyLock ){
            _ready = true ;
            _readyLock.notifyAll();
        }
     }else{
     }
     
   }
   public synchronized void   prepareRemoval( KillEvent ce ){
     _state = "Removing" ;
     _nucleus.say( "PrepareRemoval initiated"+ce ) ;
     _nucleus.say( "PrepareRemoval : removing route" ) ;
     if( _route != null )_nucleus.routeDelete( _route ) ;
     _route = null ;
     synchronized( _readyLock ){
        if( ! _ready ){
           _nucleus.say( "PrepareRemoval : waiting for last message to be processed" ) ;
           try{ _readyLock.wait()  ; }catch(InterruptedException ie){}
        } 
     }
     _nucleus.say( "PrepareRemoval : closing streams" ) ;
     try{
           _input.close();
           _output.close() ;
           _socket.close() ;
     }catch( Exception nsea ){
           _nucleus.say( " Problem in i/o : "+nsea ) ;
     }
     _state = "Dead" ;
   }
   public void   exceptionArrived( ExceptionEvent ce ){
     _nucleus.say( " exceptionArrived "+ce ) ;
   }
 
}