package dmg.util.db ;

import java.util.Enumeration;

public class DbResourceHandle implements DbLockable, DbRecordable {
   private int               _refCounter;
   private DbResourceHandler _creator;
   private DbRecordable      _resource;
   private String            _name;
   private boolean           _isValid    = true ;
   public DbResourceHandle( String name ,
                            DbResourceHandler creator ,
                            DbRecordable resource ){
       _name     = name ;
       _creator  = creator ;
       _resource = resource ;


   }
   public String  getName(){ return _name ; }
   public void    isValid( boolean isValid ){ _isValid  = isValid ; }
   public boolean isValid(){ return _isValid ; }

   @Override
   public void open( int flags )
          throws DbLockException,
                 InterruptedException {
       if( ! _isValid ) {
           throw new DbLockException("Object no longer exists");
       }
       _resource.open( flags ) ;
   }
   @Override
   public void close() throws DbLockException {
       _resource.close() ;
   }
   @Override
   public void setAttribute( String name , String attribute ) {
       _resource.setAttribute( name , attribute ) ;
   }

   @Override
   public void setAttribute( String name , String[] attribute ){
       _resource.setAttribute( name , attribute ) ;
   }

   @Override
   public Object getAttribute( String name ) {
     return _resource.getAttribute( name ) ;
   }

   @Override
   public Enumeration<String> getAttributes(){
     return _resource.getAttributes() ;
   }

   @Override
   public void remove(){
     _resource.remove() ;
   }
   @Override
   protected void finalize() throws Throwable {
       System.out.println( "Decrementing "+_name ) ;
       _creator.unlinkResource( this ) ;
       super.finalize();
   }
   public String toString(){
      StringBuilder sb = new StringBuilder() ;
      sb.append("Name : ").append(getName()).append("\n");
      sb.append( _resource.toString() ) ;
      return sb.toString() ;
   }

}
