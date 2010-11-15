/*
 * Reserve.java
 *
 * Created on July 20, 2006, 8:56 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package diskCacheV111.services.space.message;

import diskCacheV111.vehicles.Message;
import diskCacheV111.util.RetentionPolicy;
import diskCacheV111.util.AccessLatency;
import diskCacheV111.services.space.Space;

/**
 *
 * @author timur
 */
public class GetSpaceMetaData extends Message{
    static final long serialVersionUID = -7198244480807795469L;
    private long[] spaceTokens;
    private Space[] spaces;
    /** Creates a new instance of Reserve */
    public GetSpaceMetaData(long [] spaceTokens) {
        this.spaceTokens = spaceTokens;
        setReplyRequired(true);
    }
    
    public long[] getSpaceTokens() {
        return spaceTokens;
    }

    public void setSpaceToken(long spaceTokens[]) {
        this.spaceTokens = spaceTokens;
    }

    public Space[] getSpaces() {
        return spaces;
    }

    public void setSpaces(Space[] spaces) {
        this.spaces = spaces;
    }

    
}