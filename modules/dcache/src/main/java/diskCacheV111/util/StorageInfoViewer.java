//   $Id: StorageInfoViewer.java,v 1.1 2003-08-05 16:14:06 cvs Exp $

package diskCacheV111.util ;
import  diskCacheV111.vehicles.* ;
import  java.io.* ;

public class StorageInfoViewer {



   public static void main( String [] args )
   {

       if( args.length < 1 ){
          System.err.println("Usage ... <objFile> ... " ) ;
          System.exit(4);

       }
       for( int i = 0 ; i < args.length ; i++ ){
          String filename = args[i] ;
          try{
             ObjectInputStream ois =
                  new ObjectInputStream(
                         new FileInputStream( new File( filename ) ) ) ;

             try{

                Object o = null ;
                while( ( o = ois.readObject() ) != null ){

                    System.out.println( "------------------------------------------------" ) ;
                    System.out.println( "Filename : "+filename ) ;
                    System.out.println( "Class    : "+o.getClass().getName() ) ;
                    System.out.println( "Content  : ");
                    if( o instanceof StorageInfo ){
                        StorageInfo info = (StorageInfo)o ;
                        System.out.println("  HSM Type     : "+info.getHsm() ) ;
                        System.out.println("  StorageClass : "+info.getStorageClass() ) ;
                        System.out.println("  Bitfile Id   : "+info.getBitfileId() ) ;
                        String cacheClass = info.getCacheClass() ;
                        System.out.println("  Cache Class  : "+
                                ( cacheClass == null ? "None" : cacheClass ) ) ;
                        System.out.println("  File Size    : "+info.getFileSize() ) ;
                        System.out.println("  Created Only : "+info.isCreatedOnly() ) ;
                        System.out.println("  Stored       : "+info.isStored() ) ;
                        String [] flags = { "flag-s" , "flag-l" , "flag-c" } ;
                        for( int j = 0 ; j < flags.length ; j++ ){
                           String key   = flags[j] ;
                           String value = info.getKey(flags[j]) ;
                           value = value == null ? "N.N" : value ;
                           System.out.println("  Flag : "+key+" -> "+value ) ;
                        }
                    }else{
                       System.out.println( o.toString() ) ;
                    }

                }

             }catch(EOFException eofe ){
             }finally{
                try{ ois.close() ; }catch(Exception ee ){}
             }
          }catch( Exception ee ){
             ee.printStackTrace();
             System.out.println("Problem with file : "+filename+" : "+ee.getMessage());
          }

       }
       System.exit(0);


   }

}