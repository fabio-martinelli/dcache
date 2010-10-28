package diskCacheV111.vehicles;

public class OSMStorageInfo extends GenericStorageInfo {
 
   static final long serialVersionUID = 4260226401319935542L;

   private final String _store;
   private final String _group;

   public OSMStorageInfo( String store , String group ){
	   super();
      setHsm("osm");
      _store = store ;
      _group = group ;
      setIsNew(true) ;
   }
   public OSMStorageInfo( String store , String group , String bfid ){
	   super();
       setHsm("osm");
      _store = store ;
      _group = group ;
      setBitfileId(bfid) ;
      setIsNew(false) ;

   }
   public String getStorageClass() {
      return (_store==null?"<Unknown>":_store)+":"+
             (_group==null?"<Unknown>":_group) ;
   }
   public String getStore(){ return _store ; }
   public String getStorageGroup(){ return _group ; }
   public String getKey( String key ){
      if( key.equals("store") )return _store ; 
      else if( key.equals("group") )return _group ;
      else return super.getKey(key) ;
   }
   public String toString(){
      return super.toString()+
             "store="+(_store==null?"<Unknown>":_store)+
             ";group="+(_group==null?"<Unknown>":_group)+
             ";bfid="+getBitfileId()+
             ";" ;
             
   }
}
 