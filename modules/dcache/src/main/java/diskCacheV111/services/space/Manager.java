// -*- c-basic-offset: 8; -*-
//______________________________________________________________________________
//
// Space Manager - cell that handles space reservation management in SRM
//                 essentially a layer on top of a database
// database schema is described in ManagerSchemaConstants
//
// there are three essential tables:
//
//      +------------+  +--------+  +------------+
//      |srmlinkgroup|-<|srmspace|-<|srmspacefile|
//      +------------+  +--------+  +------------+
// srmlinkgroup contains field that caches sum(size-usedsize) of all space
// reservations belonging to the linkgroup. Field is called reservedspaceinbytes
//
// srmspace  contains fields that caches sum(size) of all files from srmspace
// that belong to this space reservation. Fields are usedspaceinbytes
//  (for files in state STORED) and allocatespaceinbytes
//  (for files in states RESERVED or TRANSFERRING)
//
// each time a space reservation is added/removed , reservedspaceinbytes in
// srmlinkgroup is updated
//
// each time a file is added/removed, usedspaceinbytes, allocatespaceinbytes and
// reservedspaceinbytes are updated depending on file state
//
//                                    Dmitry Litvintsev (litvinse@fnal.gov)
// $Id: Manager.java 9764 2008-07-07 17:48:24Z litvinse $
// $Author: litvinse $
//______________________________________________________________________________
package diskCacheV111.services.space;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Longs;
import dmg.cells.nucleus.*;
import org.dcache.poolmanager.Utils;
import org.dcache.util.CDCExecutorServiceDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import javax.annotation.Nonnull;
import javax.security.auth.Subject;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import diskCacheV111.poolManager.PoolPreferenceLevel;
import diskCacheV111.poolManager.PoolSelectionUnit;
import diskCacheV111.services.space.message.CancelUse;
import diskCacheV111.services.space.message.ExtendLifetime;
import diskCacheV111.services.space.message.GetFileSpaceTokensMessage;
import diskCacheV111.services.space.message.GetLinkGroupIdsMessage;
import diskCacheV111.services.space.message.GetLinkGroupNamesMessage;
import diskCacheV111.services.space.message.GetLinkGroupsMessage;
import diskCacheV111.services.space.message.GetSpaceMetaData;
import diskCacheV111.services.space.message.GetSpaceTokenIdsMessage;
import diskCacheV111.services.space.message.GetSpaceTokens;
import diskCacheV111.services.space.message.GetSpaceTokensMessage;
import diskCacheV111.services.space.message.Release;
import diskCacheV111.services.space.message.Reserve;
import diskCacheV111.services.space.message.Use;
import diskCacheV111.util.AccessLatency;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.DBManager;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.IoPackage;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.PnfsId;
import diskCacheV111.util.RetentionPolicy;
import diskCacheV111.util.VOInfo;
import diskCacheV111.vehicles.DoorTransferFinishedMessage;
import diskCacheV111.vehicles.IpProtocolInfo;
import diskCacheV111.vehicles.Message;
import diskCacheV111.vehicles.PnfsDeleteEntryNotificationMessage;
import diskCacheV111.vehicles.PoolAcceptFileMessage;
import diskCacheV111.vehicles.PoolFileFlushedMessage;
import diskCacheV111.vehicles.PoolLinkGroupInfo;
import diskCacheV111.vehicles.PoolMgrSelectWritePoolMsg;
import diskCacheV111.vehicles.PoolRemoveFilesMessage;
import diskCacheV111.vehicles.ProtocolInfo;
import diskCacheV111.vehicles.StorageInfo;

import dmg.util.Args;

import org.dcache.auth.FQAN;
import org.dcache.auth.Subjects;
import org.dcache.poolmanager.PoolMonitor;
import org.dcache.util.JdbcConnectionPool;
import org.dcache.vehicles.FileAttributes;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

/**
 *   <pre> Space Manager dCache service provides ability
 *    \to reserve space in the pool linkGroups
 *
 *
 * @author  timur
 */
public final class Manager
        extends AbstractCellComponent
        implements CellCommandListener,
                   CellMessageReceiver,
                   Runnable {

        private static final long EAGER_LINKGROUP_UPDATE_PERIOD = 1000;

        private long updateLinkGroupsPeriod;
        private long currentUpdateLinkGroupsPeriod = EAGER_LINKGROUP_UPDATE_PERIOD;
        private long expireSpaceReservationsPeriod;

        private boolean deleteStoredFileRecord;

        private Thread updateLinkGroups;
        private Thread expireSpaceReservations;

        private AccessLatency     defaultAccessLatency;
        private RetentionPolicy defaultRetentionPolicy;

        private boolean reserveSpaceForNonSRMTransfers;
        private boolean returnFlushedSpaceToReservation;
        private boolean cleanupExpiredSpaceFiles;
        private String linkGroupAuthorizationFileName;
        private boolean spaceManagerEnabled;

        private CellPath poolManager;
        private PnfsHandler pnfs;

        private SpaceManagerAuthorizationPolicy authorizationPolicy;

        private Executor executor;

        private JdbcConnectionPool connection_pool;
        private DBManager dbManager;
        private static final Logger logger = LoggerFactory.getLogger(Manager.class);
        private static final IoPackage<File> fileIO = new FileIO();
        private static final IoPackage<Space> spaceReservationIO = new SpaceReservationIO();
        private static final IoPackage<LinkGroup> linkGroupIO = new LinkGroupIO();
        private PoolMonitor poolMonitor;

        @Required
        public void setPoolManager(CellPath poolManager)
        {
                this.poolManager = poolManager;
        }

        @Required
        public void setPnfsHandler(PnfsHandler pnfs)
        {
                this.pnfs = pnfs;
        }

        public void setPoolMonitor(PoolMonitor poolMonitor)
        {
                this.poolMonitor = poolMonitor;
        }

        @Required
        public void setSpaceManagerEnabled(boolean enabled)
        {
                this.spaceManagerEnabled = enabled;
        }

        @Required
        public void setUpdateLinkGroupsPeriod(long updateLinkGroupsPeriod)
        {
                this.updateLinkGroupsPeriod = updateLinkGroupsPeriod;
        }

        @Required
        public void setExpireSpaceReservationsPeriod(long expireSpaceReservationsPeriod)
        {
                this.expireSpaceReservationsPeriod = expireSpaceReservationsPeriod;
        }


        @Required
        public void setDefaultRetentionPolicy(RetentionPolicy defaultRetentionPolicy)
        {
                this.defaultRetentionPolicy = defaultRetentionPolicy;
        }

        @Required
        public void setDefaultAccessLatency(AccessLatency defaultAccessLatency)
        {
                this.defaultAccessLatency = defaultAccessLatency;
        }

        @Required
        public void setReserveSpaceForNonSRMTransfers(boolean reserveSpaceForNonSRMTransfers)
        {
                this.reserveSpaceForNonSRMTransfers = reserveSpaceForNonSRMTransfers;
        }

        @Required
        public void setDeleteStoredFileRecord(boolean  deleteStoredFileRecord)
        {
                this.deleteStoredFileRecord = deleteStoredFileRecord;
        }

        @Required
        public void setCleanupExpiredSpaceFiles(boolean cleanupExpiredSpaceFiles)
        {
                this.cleanupExpiredSpaceFiles = cleanupExpiredSpaceFiles;
        }

        @Required
        public void setReturnFlushedSpaceToReservation(boolean returnFlushedSpaceToReservation)
        {
                this.returnFlushedSpaceToReservation = returnFlushedSpaceToReservation;
        }

        @Required
        public void setLinkGroupAuthorizationFileName(String linkGroupAuthorizationFileName)
        {
                this.linkGroupAuthorizationFileName = linkGroupAuthorizationFileName;
        }

        @Required
        public void setExecutor(ExecutorService executor)
        {
            this.executor = new CDCExecutorServiceDecorator(executor);
        }

        @Required
        public void setDbManager(DBManager manager)
        {
                dbManager = manager;
                connection_pool = dbManager.getConnectionPool();
        }

        @Required
        public void setAuthorizationPolicy(SpaceManagerAuthorizationPolicy authorizationPolicy)
        {
                this.authorizationPolicy = authorizationPolicy;
        }


        public void start() throws Exception
        {
                dbinit();
                (updateLinkGroups = new Thread(this,"UpdateLinkGroups")).start();
                (expireSpaceReservations = new Thread(this,"ExpireThreadReservations")).start();
        }

        public void stop()
        {
                if (updateLinkGroups != null) {
                        updateLinkGroups.interrupt();
                }
                if (expireSpaceReservations != null) {
                        expireSpaceReservations.interrupt();
                }
        }


        @Override
        public void getInfo(PrintWriter printWriter) {
                printWriter.println("space.Manager "+getCellName());
                printWriter.println("spaceManagerEnabled="+spaceManagerEnabled);
                printWriter.println("updateLinkGroupsPeriod="
                                    + updateLinkGroupsPeriod);
                printWriter.println("expireSpaceReservationsPeriod="
                                    + expireSpaceReservationsPeriod);
                printWriter.println("deleteStoredFileRecord="
                                    + deleteStoredFileRecord);
                printWriter.println("defaultLatencyForSpaceReservations="
                                    + defaultAccessLatency);
                printWriter.println("reserveSpaceForNonSRMTransfers="
                                    + reserveSpaceForNonSRMTransfers);
                printWriter.println("returnFlushedSpaceToReservation="
                                    + returnFlushedSpaceToReservation);
                printWriter.println("linkGroupAuthorizationFileName="
                                    + linkGroupAuthorizationFileName);
        }

        public static final String hh_release = " <spaceToken> [ <bytes> ] # release the space " +
                "reservation identified by <spaceToken>" ;

        public String ac_release_$_1_2(Args args) throws Exception {
                long reservationId = Long.parseLong( args.argv(0));
                if(args.argc() == 1) {
                        Space space = updateSpaceState(reservationId,SpaceState.RELEASED);
                        return space.toString();
                }
                else {
                        return "partial release is not supported yet";
                }
        }

        public static final String hh_update_space_reservation = " [-size=<size>]  [-lifetime=<lifetime>] [-vog=<vogroup>] [-vor=<vorole>] <spaceToken> \n"+
                "                                                     # set new size and/or lifetime for the space token \n " +
                "                                                     # valid examples of size: 1000, 100kB, 100KB, 100KiB, 100MB, 100MiB, 100GB, 100GiB, 10.5TB, 100TiB \n" +
                "                                                     # see http://en.wikipedia.org/wiki/Gigabyte for explanation \n"+
                "                                                     # lifetime is in seconds (\"-1\" means infinity or permanent reservation";

        private static long stringToSize(String s)
        {
                long size;
                int endIndex;
                int startIndex=0;
                if (s.endsWith("kB") || s.endsWith("KB")) {
                        endIndex=s.indexOf("KB");
                        if (endIndex==-1) {
                                endIndex=s.indexOf("kB");
                        }
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1000L : (long)(Double.parseDouble(sSize)*1.e+3+0.5);
                }
                else if (s.endsWith("KiB")) {
                        endIndex=s.indexOf("KiB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1024L : (long)(Double.parseDouble(sSize)*1024.+0.5);
                }
                else if (s.endsWith("MB")) {
                        endIndex=s.indexOf("MB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1000000L : (long)(Double.parseDouble(sSize)*1.e+6+0.5);
                }
                else if (s.endsWith("MiB")) {
                        endIndex=s.indexOf("MiB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1048576L : (long)(Double.parseDouble(sSize)*1048576.+0.5);
                }
                else if (s.endsWith("GB")) {
                        endIndex=s.indexOf("GB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1000000000L : (long)(Double.parseDouble(sSize)*1.e+9+0.5);
                }
                else if (s.endsWith("GiB")) {
                        endIndex=s.indexOf("GiB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1073741824L : (long)(Double.parseDouble(sSize)*1073741824.+0.5);
                }
                else if (s.endsWith("TB")) {
                        endIndex=s.indexOf("TB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1000000000000L : (long)(Double.parseDouble(sSize)*1.e+12+0.5);
                }
                else if (s.endsWith("TiB")) {
                        endIndex=s.indexOf("TiB");
                        String sSize = s.substring(startIndex,endIndex);
                        size    = sSize.isEmpty() ? 1099511627776L : (long)(Double.parseDouble(sSize)*1099511627776.+0.5);
                }
                else {
                        size = Long.parseLong(s);
                }
                if (size<0L) {
                        throw new IllegalArgumentException("size have to be non-negative");
                }
                return size;
        }


        public String ac_update_space_reservation_$_1(Args args) throws Exception {
                long reservationId = Long.parseLong(args.argv(0));
                String sSize     = args.getOpt("size");
                String sLifetime = args.getOpt("lifetime");
                String voRole      = args.getOpt("vor");
                String voGroup     = args.getOpt("vog");
                if (sLifetime==null&&
                    sSize==null&&
                    voRole==null&&
                    voGroup==null) {
                        return "Need to specify at least one option \"-lifetime\", \"-size\" \"-vog\" or \"-vor\". If -lifetime=\"-1\"  then the reservation will not expire";
                }
                Long longLifetime = null;
                if (sLifetime != null) {
                        long lifetime = Long.parseLong(sLifetime);
                        longLifetime = ( lifetime == -1 ) ? new Long(-1) : new Long ( lifetime * 1000 );
                }
                if (voRole!=null||voGroup!=null) {
                        // check that linkgroup allows these role/group combination
                        try {
                                Space space = getSpace(reservationId);
                                long lid = space.getLinkGroupId();
                                LinkGroup lg = getLinkGroup(lid);
                                boolean foundMatch=false;
                                // this will keep the same group/role
                                // if one of then is not specified:
                                if (voGroup==null) {
                                    voGroup = space.getVoGroup();
                                }
                                if (voRole==null) {
                                    voRole = space.getVoRole();
                                }
                                for (VOInfo info : lg.getVOs()) {
                                        if (info.match(voGroup,voRole)) {
                                                foundMatch=true;
                                                break;
                                        }
                                }
                                if (!foundMatch) {
                                        throw new IllegalArgumentException("cannot change voGroup:voRole to "+
                                                                           voGroup+ ':' +voRole+
                                                                           ". Supported vogroup:vorole pairs for this spacereservation\n"+
                                                                           Joiner.on('\n').join(lg.getVOs()));
                                }
                        }
                        catch (SQLException e) {
                                return e.toString();
                        }
                        catch (Exception  e) {
                                return e.toString();
                        }
                }
                try {
                        updateSpaceReservation(reservationId,
                                               voGroup,
                                               voRole,
                                               null,
                                               null,
                                               null,
                                               (sSize != null ? stringToSize(sSize) : null),
                                               longLifetime,
                                               null,
                                               null);
                }
                catch (SQLException e) {
                        return e.toString();
                }
                StringBuilder sb = new StringBuilder();
                listSpaceReservations(false,
                                      String.valueOf(reservationId),
                                      null,
                                      null,
                                      null,
                                      null,
                                      null,
                                      sb);
                return sb.toString();
        }

        public static final String hh_update_link_groups = " #triggers update of the link groups";
        public String ac_update_link_groups_$_0(Args args)
        {
                synchronized(updateLinkGroupsSyncObject) {
                        updateLinkGroupsSyncObject.notify();
                }
                return "update started";
        }

        public static final String hh_ls = " [-lg=LinkGroupName] [-lgid=LinkGroupId] [-vog=vogroup] [-vor=vorole] [-desc=description] [-l] <id> # list space reservations";

        public String ac_ls_$_0_1(Args args) throws Exception {
                String lgName        = args.getOpt("lg");
                String lgid          = args.getOpt("lgid");
                String voGroup       = args.getOpt("vog");
                String voRole        = args.getOpt("vor");
                String description   = args.getOpt("desc");
                boolean isLongFormat = args.hasOption("l");
                String id = null;
                if (args.argc() == 1) {
                        id = args.argv(0);
                }
                StringBuilder sb = new StringBuilder();
                if (description != null && id !=null ) {
                        sb.append("Do not handle \"desc\" and id simultaneously\n");
                        return sb.toString();
                }

                if (lgName==null&&lgid==null&&voGroup==null&&description==null) {
                        sb.append("Reservations:\n");
                }
                listSpaceReservations(isLongFormat,
                                      id,
                                      lgName,
                                      lgid,
                                      description,
                                      voGroup,
                                      voRole,
                                      sb);
                if (lgName==null&&lgid==null&&voGroup==null&&description==null&id==null) {
                        sb.append("\n\nLinkGroups:\n");
                        listLinkGroups(isLongFormat,false,null,sb);
                }
                return sb.toString();
        }


        private void listSpaceReservations(boolean isLongFormat,
                                           String id,
                                           String linkGroupName,
                                           String linkGroupId,
                                           String description,
                                           String group,
                                           String role,
                                           StringBuilder sb) throws Exception {
                Set<Space> spaces;
                long lgId = 0;
                LinkGroup lg = null;
                if (linkGroupId!=null) {
                        lgId = Long.parseLong(linkGroupId);
                        lg   = getLinkGroup(lgId);
                }
                if (linkGroupName!=null) {
                        lg = getLinkGroupByName(linkGroupName);
                        if (lgId!=0) {
                                if (lg.getId()!=lgId) {
                                        sb.append("Cannot find LinkGroup with id=").
                                                append(linkGroupId).
                                                append(" and name=").
                                                append(linkGroupName);
                                        return;
                                }
                        }
                }
                if (lg!=null) {
                        sb.append("Found LinkGroup:\n");
                        lg.toStringBuilder(sb);
                        sb.append('\n');
                }

                if(id != null) {
                        Long longid = Long.valueOf(id);
                        try {
                                spaces=dbManager.selectPrepared(spaceReservationIO,
                                                              SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_ID,
                                                              longid);
                                if (spaces.isEmpty()) {
                                        if(lg==null) {
                                                sb.append("Space with id=").
                                                        append(id).
                                                        append(" not found ");
                                        }
                                        else {
                                                sb.append("LinkGroup with id=").
                                                        append(lg.getId()).
                                                        append(" and name=").
                                                        append(lg.getName()).
                                                        append(
                                                        " does not contain space with id=").
                                                        append(id);
                                        }
                                        return;
                                }
                                for (Space space : spaces ) {
                                        if (lg!=null) {
                                                if (space.getLinkGroupId()!=lg.getId()) {
                                                        sb.append("LinkGroup with id=").
                                                                append(lg.getId()).
                                                                append(" and name=").
                                                                append(lg.getName()).
                                                                append(" does not contain space with id=").
                                                                append(id);
                                                }
                                        }
                                        else {
                                                space.toStringBuilder(sb);
                                        }
                                        sb.append('\n');
                                }
                                return;
                        }
                        catch(SQLException e) {
                                if(lg==null) {
                                        sb.append("Space with id=").
                                                append(id).
                                                append(" not found ");
                                }
                                else {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(lg.getName()).
                                                append(" does not contain space with id=").
                                                append(id);
                                }
                                return;
                        }
                }
                if (linkGroupName==null&&linkGroupId==null&&description==null&&group==null&&role==null){
                        try {
                                spaces=dbManager.selectPrepared(spaceReservationIO,
                                                              SpaceReservationIO.SELECT_CURRENT_SPACE_RESERVATIONS);
                                int count = spaces.size();
                                long totalReserved = 0;
                                for (Space space : spaces) {
                                        totalReserved += space.getSizeInBytes();
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                                sb.append("total number of reservations: ").append(count).append('\n');
                                sb.append("total number of bytes reserved: ").append(totalReserved);
                                return;

                        }
                        catch(SQLException sqle) {
                                sb.append(sqle.getMessage());
                                return;
                        }
                }
                if (description==null&&group==null&&role==null&&lg!=null) {
                        try {
                                spaces=dbManager.selectPrepared(spaceReservationIO,
                                                              SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_LINKGROUP_ID,
                                                              lg.getId());
                                if (spaces.isEmpty()) {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(lg.getName()).
                                                append(" does not contain any space reservations\n");
                                        return;
                                }
                                for (Space space : spaces) {
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                                return;
                        }
                        catch(SQLException e) {
                                sb.append("LinkGroup with id=").
                                        append(lg.getId()).
                                        append(" and name=").
                                        append(lg.getName()).
                                        append(" does not contain any space reservations\n");
                                return;
                        }

                }
                if (description!=null) {
                        try {
                                if (lg==null) {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                                 SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_DESC,
                                                                                 description);
                                }
                                else {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_DESC_AND_LINKGROUP_ID,
                                                                      description,
                                                                      lg.getId());
                                }
                                if (spaces.isEmpty()) {
                                        if (lg==null) {
                                                sb.append("Space with description ").
                                                        append(description).
                                                        append(" not found ");
                                        }
                                        else {
                                                sb.append("LinkGroup with id=").
                                                        append(lg.getId()).
                                                        append(" and name=").
                                                        append(lg.getName()).
                                                        append(" does not contain space with description ").
                                                        append(description);
                                        }
                                        return;
                                }
                                for (Space space : spaces) {
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                                return;
                        }
                        catch(SQLException e) {
                                if (lg==null) {
                                        sb.append("Space with description ").
                                                append(description).
                                                append(" not found ");
                                }
                                else {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(lg.getName()).
                                                append(" does not contain space with description ").
                                                append(description);
                                }
                                return;
                        }
                }
                if (role!=null&&group!=null) {
                        try {
                                if (lg==null) {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOGROUP_AND_VOROLE,
                                                                      group,
                                                                      role);
                                }
                                else {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOGROUP_AND_VOROLE_AND_LINKGROUP_ID,
                                                                      group,
                                                                      role,
                                                                      lg.getId());

                                }
                                if (spaces.isEmpty()) {
                                        if (lg==null) {
                                                sb.append("Space with vorole ").
                                                        append(role).
                                                        append(" and vogroup ").
                                                        append(group).
                                                        append(" not found ");
                                        }
                                        else {
                                                sb.append("LinkGroup with id=").
                                                        append(lg.getId()).
                                                        append(" and name=").
                                                        append(lg.getName()).
                                                        append(" does not contain space with vorole ").
                                                        append(role).
                                                        append(" and vogroup ").
                                                        append(group);
                                        }
                                        return;
                                }
                                for (Space space : spaces) {
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                                return;
                        }
                        catch(SQLException e) {
                                if (lg==null) {
                                        sb.append("Space with vorole ").
                                                append(role).
                                                append(" and vogroup ").
                                                append(group).
                                                append(" not found ");
                                }
                                else {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(lg.getName()).
                                                append(" does not contain space with vorole ").
                                                append(role).
                                                append(" and vogroup ").
                                                append(group);
                                }
                                return;
                        }
                }
                if (group!=null) {
                        try {
                                if (lg==null) {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOGROUP,
                                                                      group);
                                }
                                else {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOGROUP_AND_LINKGROUP_ID,
                                                                      group,
                                                                      lg.getId());
                                }
                                if (spaces.isEmpty()) {
                                        if (lg==null) {
                                                sb.append("Space with vogroup ").
                                                        append(group).
                                                        append(" not found ");
                                        }
                                        else {
                                                sb.append("LinkGroup with id=").
                                                        append(lg.getId()).
                                                        append(" and name=").
                                                        append(
                                                        " does not contain space with vogroup=").
                                                        append(group);
                                        }
                                        return;
                                }
                                for (Space space : spaces) {
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                                return;
                        }
                        catch(SQLException e) {
                                if (lg==null) {
                                        sb.append("Space with vogroup ").
                                                append(group).
                                                append(" not found ");
                                }
                                else {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(
                                                " does not contain space with vogroup=").
                                                append(group);
                                }
                                return;
                        }
                }
                if (role!=null) {
                        try {
                                if (lg==null) {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                        SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOROLE,
                                                                      group);
                                }
                                else {
                                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                                      SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_VOROLE_AND_LINKGROUP_ID,
                                                                      group,
                                                                      lg.getId());
                                }
                                if (spaces.isEmpty()) {
                                        if (lg==null) {
                                                sb.append("Space with vogroup ").
                                                        append(group).
                                                        append(" not found ");
                                        }
                                        else {
                                                sb.append("LinkGroup with id=").
                                                        append(lg.getId()).
                                                        append(" and name=").
                                                        append(" does not contain space with vorole=").
                                                        append(role);
                                        }
                                        return;
                                }
                                for (Space space : spaces) {
                                        space.toStringBuilder(sb);
                                        sb.append('\n');
                                }
                        }
                        catch(SQLException e) {
                                if (lg==null) {
                                        sb.append("Space with vogroup ").
                                                append(group).
                                                append(" not found ");
                                }
                                else {
                                        sb.append("LinkGroup with id=").
                                                append(lg.getId()).
                                                append(" and name=").
                                                append(" does not contain space with vorole=").
                                                append(role);
                                }
                        }
                }

        }

        private void listLinkGroups(boolean isLongFormat,
                                    boolean all,
                                    String id,
                                    StringBuilder sb)
        {
                Set<LinkGroup> groups;
                if(id != null) {
                        long longid = Long.parseLong(id);
                        try {
                                LinkGroup lg=getLinkGroup(longid);
                                lg.toStringBuilder(sb);
                                sb.append('\n');
                                return;
                        }
                        catch(SQLException e) {
                                sb.append("LinkGroup  with id=").
                                        append(id).
                                        append(" not found ");
                                return;
                        }
                }
                try {
                        if(all) {
                                groups=dbManager.selectPrepared(linkGroupIO,
                                                              LinkGroupIO.SELECT_ALL_LINKGROUPS);
                        }
                        else {
                                groups=dbManager.selectPrepared(linkGroupIO,
                                                              LinkGroupIO.SELECT_CURRENT_LINKGROUPS,
                                                              latestLinkGroupUpdateTime);
                        }
                        int count = groups.size();
                        long totalReservable = 0L;
                        long totalReserved   = 0L;
                        for (LinkGroup g : groups) {
                                totalReservable  += g.getAvailableSpaceInBytes();
                                totalReserved    += g.getReservedSpaceInBytes();
                                g.toStringBuilder(sb);
                                sb.append('\n');
                        }
                        sb.append("total number of linkGroups: ").
                                append(count).append('\n');
                        sb.append("total number of bytes reservable: ").
                                append(totalReservable).append('\n');
                        sb.append("total number of bytes reserved  : ").
                                append(totalReserved).append('\n');
                        sb.append("last time all link groups were updated: ").
                                append((new Date(latestLinkGroupUpdateTime)).toString()).
                                append('(').append(latestLinkGroupUpdateTime).
                                append(')');
                }
                catch(SQLException sqle) {
                        sb.append(sqle.getMessage());
                }
        }

        public static final String hh_ls_link_groups = " [-l] [-a]  <id> # list link groups";
        public String ac_ls_link_groups_$_0_1(Args args)
        {
                boolean isLongFormat = args.hasOption("l");
                boolean all = args.hasOption("a");
                String id = null;
                if (args.argc() == 1) {
                        id = args.argv(0);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("\n\nLinkGroups:\n");
                listLinkGroups(isLongFormat,all,id,sb);
                return sb.toString();
        }

        public static final String hh_ls_file_space_tokens = " <pnfsId>|<pnfsPath> # list space tokens " +
                "that contain a file";

        public String ac_ls_file_space_tokens_$_1(Args args) throws Exception {
                String  pnfsPath = args.argv(0);
                PnfsId pnfsId;
                try {
                        pnfsId = new PnfsId(pnfsPath);
                        pnfsPath = null;
                }
                catch(Exception e) {
                        pnfsId = null;
                }
                long[] tokens= getFileSpaceTokens(pnfsId, pnfsPath);
                if (tokens.length > 0) {
                    return Joiner.on('\n').join(Longs.asList(tokens));
                }
                else {
                    return "no space tokens found for file: " + args.argv(0);
                }
        }

        public final String hh_reserve = "  [-vog=voGroup] [-vor=voRole] " +
                "[-acclat=AccessLatency] [-retpol=RetentionPolicy] [-desc=Description] " +
                " [-lgid=LinkGroupId]" +
                " [-lg=LinkGroupName]" +
                " <sizeInBytes> <lifetimeInSecs (use quotes around negative one)> \n"+
                " default value for AccessLatency is "+defaultAccessLatency + '\n' +
                " default value for RetentionPolicy is "+defaultRetentionPolicy;


        public String ac_reserve_$_2(Args args) throws Exception {
                long sizeInBytes;
                try {
                        sizeInBytes=stringToSize(args.argv(0));
                }
                catch (Exception e) {
                        return "Cannot convert size specified ("
                               +args.argv(0)
                               +") to non-negative number. \n"
                               +"Valid definition of size:\n"+
                                "\t\t - a number of bytes (long integer less than 2^64) \n"+
                                "\t\t - 100kB, 100KB, 100KiB, 100MB, 100MiB, 100GB, 100GiB, 10.5TB, 100TiB \n"+
                                "see http://en.wikipedia.org/wiki/Gigabyte for explanation";
                }
                long lifetime=Long.parseLong(args.argv(1));
                if(lifetime > 0) {
                        lifetime *= 1000;
                }
                String voGroup       = args.getOpt("vog");
                String voRole        = args.getOpt("vor");
                String description   = args.getOpt("desc");
                String latencyString = args.getOpt("acclat");
                String policyString  = args.getOpt("retpol");

                AccessLatency latency = latencyString==null?
                    defaultAccessLatency:AccessLatency.getAccessLatency(latencyString);
                RetentionPolicy policy = policyString==null?
                    defaultRetentionPolicy:RetentionPolicy.getRetentionPolicy(policyString);

                String lgIdString = args.getOpt("lgid");
                String lgName     = args.getOpt("lg");
                if(lgIdString != null && lgName != null) {
                        return "Error: both exclusive options -lg and -lgid are specified";
                }
                long reservationId;
                if(lgIdString == null && lgName == null) {
                        try {
                                reservationId=reserveSpace(voGroup,
                                                           voRole,
                                                           sizeInBytes,
                                                           latency,
                                                           policy,
                                                           lifetime,
                                                           description);
                        }
                        catch (Exception e) {
                                return "Failed to find likgroup taht can accommodate this space reservation. \n"+
                                       e.getMessage()+'\n'+
                                       "check that you have any link groups that satisfy the following criteria: \n"+
                                       "\t can fit the size you are requesting ("+sizeInBytes+")\n"+
                                       "\t vogroup,vorole you specified ("+
                                       voGroup+ ',' +voRole+
                                       ") are allowed, and \n"+
                                       "\t retention policy and access latency you specified ("+
                                       policyString+ ',' +latencyString+
                                       ") are allowed \n";
                        }
                }
                else {
                        long lgId;
                        LinkGroup lg;
                        if (lgIdString != null){
                                lgId =Long.parseLong(lgIdString);
                                lg   = getLinkGroup(lgId);
                                if(lg ==null) {
                                        return "Error, could not find link group with id = "+lgIdString+'\n';
                                }
                        }
                        else {
                                lg = getLinkGroupByName(lgName);
                                if(lg ==null) {
                                        return "Error, could not find link group with name = '"+lgName+"'\n";
                                }
                                lgId = lg.getId();
                        }

                        Long[] linkGroups = findLinkGroupIds(sizeInBytes,
                                                             voGroup,
                                                             voRole,
                                                             latency,
                                                             policy);
                        if(linkGroups.length == 0) {
                                return "Link Group "+lg+" is found, but it cannot accommodate the reservation requested, \n"+
                                        "check that the link group satisfies the following criteria: \n"+
                                        "\t it can fit the size you are requesting ("+sizeInBytes+")\n"+
                                        "\t vogroup,vorole you specified ("+voGroup+ ',' +voRole+") are allowed, and \n"+
                                        "\t retention policy and access latency you specified ("+policyString+ ',' +latencyString+") are allowed \n";
                        }

                        boolean yes=false;
                    for (Long linkGroup : linkGroups) {
                        if (linkGroup == lgId) {
                            yes = true;
                            break;
                        }
                    }
                        if (!yes) {
                                return "Link Group "+lg+
                                       " is found, but it cannot accommodate the reservation requested, \n"+
                                        "check that the link group satisfies the following criteria: \n"+
                                        "\t it can fit the size you are requesting ("+sizeInBytes+")\n"+
                                        "\t vogroup,vorole you specified ("+voGroup+ ',' +voRole+") are allowed, and \n"+
                                        "\t retention policy and access latency you specified ("+policyString+ ',' +latencyString+") are allowed \n";
                        }
                        reservationId=reserveSpaceInLinkGroup(lgId,
                                                              voGroup,
                                                              voRole,
                                                              sizeInBytes,
                                                              latency,
                                                              policy,
                                                              lifetime,
                                                              description);
                }
                Space space = getSpace(reservationId);
                return space.toString();
        }

        public static final String hh_listInvalidSpaces = " [-e] [-r] <n>" +
                " # e=expired, r=released, default is both, n=number of rows to retrieve";

        private static final int RELEASED = 1;
        private static final int EXPIRED  = 2;

        private static final String[] badSpaceType= { "released",
                                                     "expired",
                                                     "released or expired" };
        public String ac_listInvalidSpaces_$_0_3( Args args )
                throws Exception {
                int argCount       = args.optc();
                boolean doExpired  = args.hasOption( "e" );
                boolean doReleased = args.hasOption( "r" );
                int nRows = 1000;
                if (args.argc()>0) {
                        nRows = Integer.parseInt(args.argv(0));
                }
                if (nRows < 0 ) {
                        return "number of rows must be non-negative";
                }
                int listOptions = RELEASED | EXPIRED;
                if ( doExpired || doReleased ) {
                        listOptions = 0;
                        if ( doExpired ) {
                                listOptions = EXPIRED;
                                --argCount;
                        }
                        if ( doReleased ) {
                                listOptions |= RELEASED;
                                --argCount;
                        }
                }
                if ( argCount != 0 ) {
                        return "Unrecognized option.\nUsage: listInvalidSpaces" +
                                hh_listInvalidSpaces;
                }
                List< Space > expiredSpaces = listInvalidSpaces( listOptions , nRows );
                if ( expiredSpaces.isEmpty() ) {
                        return "There are no " + badSpaceType[ listOptions-1 ] + " spaces.";
                }
                return Joiner.on('\n').join(expiredSpaces);
        }

        private static final String SELECT_INVALID_SPACES=
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_TABLE_NAME +
                " WHERE state = ";

        private List< Space > listInvalidSpaces(int spaceTypes, int nRows)
                throws SQLException,
                Exception {
                String query;
                switch ( spaceTypes ) {
                case EXPIRED: // do just expired
                        query = SELECT_INVALID_SPACES + SpaceState.EXPIRED.getStateId();
                        break;
                case RELEASED: // do just released
                        query = SELECT_INVALID_SPACES + SpaceState.RELEASED.getStateId();
                        break;
                case RELEASED | EXPIRED: // do both
                        query = SELECT_INVALID_SPACES + SpaceState.EXPIRED.getStateId() +
                                " OR state = " + SpaceState.RELEASED.getStateId();
                        break;
                default: // something is broken
                        String msg = "listInvalidSpaces: got invalid space type "
                                + spaceTypes;
                        throw new Exception( msg );
                }
                Connection con = null;
                // Note that we return an empty list if "set" is empty.
                List< Space > result = new ArrayList<>();
                try {
                        con = connection_pool.getConnection();
                        logger.trace("executing statement: {}", query);
                        PreparedStatement sqlStatement = con.prepareStatement( query );
                        con.setAutoCommit(false);
                        sqlStatement.setFetchSize(10000);
                        sqlStatement.setMaxRows(nRows);
                        ResultSet set = sqlStatement.executeQuery();
                        while (set.next()) {
                                Space space=new Space(set.getLong("id"),
                                                      set.getString("voGroup"),
                                                      set.getString("voRole"),
                                                      RetentionPolicy.getRetentionPolicy(set.getInt("retentionPolicy")),
                                                      AccessLatency.getAccessLatency(set.getInt("accessLatency")),
                                                      set.getLong("linkGroupId"),
                                                      set.getLong("sizeInBytes"),
                                                      set.getLong("creationTime"),
                                                      set.getLong("lifetime"),
                                                      set.getString("description"),
                                                      SpaceState.getState(set.getInt("state")),
                                                      set.getLong("usedspaceinbytes"),
                                                      set.getLong("allocatedspaceinbytes"));
                                result.add(space);
                        }
                        set.close();
                        sqlStatement.close();
                        connection_pool.returnConnection(con);
                        con=null;
                }
                catch (SQLException sqe) {
                        if (con!=null) {
                                con.rollback();
                                connection_pool.returnFailedConnection(con);
                                con=null;
                        }
                        throw sqe;
                }
                finally {
                        if (con!=null) {
                                connection_pool.returnConnection(con);
                        }
                }
                return result;
        }


        public static final String hh_listFilesInSpace=" <space-id>";
        // @return a string containing a newline-separated list of the files in
        //         the space specified by <i>space-id</i>.

        public String ac_listFilesInSpace_$_1( Args args )
                throws SQLException, NumberFormatException
        {
                long spaceId = Long.parseLong( args.argv( 0 ) );
                // Get a list of the Invalid spaces
                Set<File> filesInSpace=listFilesInSpace(spaceId);
                if (filesInSpace.isEmpty()) {
                        return "There are no files in this space.";
                }
                return Joiner.on('\n').join(filesInSpace);
        }

        // This method returns an array of all the files in the specified space.
        private Set<File> listFilesInSpace(long spaceId)
                throws SQLException {
                return dbManager.selectPrepared(fileIO,
                                                FileIO.SELECT_BY_SPACERESERVATION_ID,
                                                spaceId);
        }

        public static final String hh_removeFilesFromSpace=
                " [-r] [-t] [-s] [-f] -d] <Space Token>"+
                "# remove expired files from space, -r(reserved) -t(transferring) -s(stored) -f(flushed)";

        public String ac_removeFilesFromSpace_$_1_4( Args args )
                throws Exception {
                long spaceId=Long.parseLong(args.argv(0));
                int optCount=args.optc();
                StringBuilder sb=new StringBuilder();
                if (optCount==0) {
                        sb.append("No option specified, will remove expired RESERVED and TRANSFERRING files\n");
                }
                boolean doReserved     = args.hasOption( "r" );
                boolean doTransferring = args.hasOption( "t" );
                boolean doStored       = args.hasOption( "s" );
                boolean doFlushed      = args.hasOption( "f" );
                Set<Space> spaces=dbManager.selectPrepared(spaceReservationIO,
                                                         SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_ID,
                                                         spaceId);
                if (spaces.isEmpty()) {
                        sb.append("Space with ").append(spaceId).append(" does not exist\n");
                        return sb.toString();
                }
                for (Space space : spaces) {
                        Set<File> files=dbManager.selectPrepared(fileIO,
                                                               FileIO.SELECT_EXPIRED_SPACEFILES1,
                                                               System.currentTimeMillis(),
                                                               space.getId());
                        for (File file : files) {
                                if (optCount==0) {
                                        if (file.getState()==FileState.STORED||
                                                file.getState()==FileState.FLUSHED) {
                                            continue;
                                        }
                                }
                                else {
                                        if (!doReserved && file.getState()==FileState.RESERVED) {
                                            continue;
                                        }
                                        if (!doTransferring && file.getState()==FileState.TRANSFERRING) {
                                            continue;
                                        }
                                        if (!doStored && file.getState()==FileState.STORED) {
                                            continue;
                                        }
                                        if (!doFlushed && file.getState()==FileState.FLUSHED) {
                                            continue;
                                        }
                                }
                                try {
                                        removeFileFromSpace(file.getId());
                                }
                                catch (SQLException e) {
                                        sb.append("Failed to remove file ").
                                                append(file).append('\n');
                                        logger.warn("failed to remove file " +
                                                "{}: {}", file, e.getMessage());
                                }
                        }
                }
                return sb.toString();
        }

        public static final String hh_remove_file = " -id=<file id> | -pnfsId=<pnfsId>  " +
                "# remove file by spacefile id or pnfsid";

        public String ac_remove_file( Args args )
                throws Exception {
                String sid     = args.getOpt("id");
                String sPnfsId = args.getOpt("pnfsId");
                if (sid!=null&&sPnfsId!=null) {
                        return "do not handle \"-id\" and \"-pnfsId\" options simultaneously";
                }
                if (sid!=null) {
                        long id = Long.parseLong(sid);
                        removeFileFromSpace(id);
                        return "removed file with id="+id;
                }
                if (sPnfsId!=null) {
                        PnfsId pnfsId = new PnfsId(sPnfsId);
                        File f = getFile(pnfsId);
                        removeFileFromSpace(f.getId());
                        return "removed file with pnfsId="+pnfsId;
                }
                return "please specify  \"-id=\" or \"-pnfsId=\" option";
        }

        private void dbinit() throws SQLException {
                insertRetentionPolicies();
                insertAccessLatencies();
        }

        private static final String countPolicies=
                "SELECT count(*) from "+
                ManagerSchemaConstants.RETENTION_POLICY_TABLE_NAME;

        private static final String insertPolicy = "INSERT INTO "+
                ManagerSchemaConstants.RETENTION_POLICY_TABLE_NAME +
                " (id, name) VALUES (?,?)" ;

        private void insertRetentionPolicies() throws  SQLException{
                RetentionPolicy[] policies = RetentionPolicy.getAllPolicies();
                Object o = dbManager.selectPrepared(1,countPolicies);
                if (o!=null && (Long) o == policies.length) {
                        return;
                }
            for (RetentionPolicy policy : policies) {
                try {
                    dbManager.insert(insertPolicy, policy.getId(), policy
                            .toString());
                } catch (SQLException sqle) {
                    logger.error("insert retention policy {} failed: {}",
                            policy, sqle.getMessage());
                }
            }
        }

        private static final String countLatencies =
                "SELECT count(*) from "+ManagerSchemaConstants.ACCESS_LATENCY_TABLE_NAME;

        private static final String insertLatency = "INSERT INTO "+
                ManagerSchemaConstants.ACCESS_LATENCY_TABLE_NAME +
                " (id, name) VALUES (?,?)";

        private void insertAccessLatencies() throws  SQLException {
                AccessLatency[] latencies = AccessLatency.getAllLatencies();
                Object o = dbManager.selectPrepared(1,countLatencies);
                if (o!=null && (Long) o == latencies.length) {
                        return;
                }
            for (AccessLatency latency : latencies) {
                try {
                    dbManager.insert(insertLatency, latency.getId(), latency
                            .toString());
                } catch (SQLException sqle) {
                    logger.error("insert access latency {} failed: {}",
                            latency, sqle.getMessage());
                }
            }
        }

//
// the code below is left w/o changes for now
//

        private static final String selectNextIdForUpdate =
                "SELECT * from "+ManagerSchemaConstants.SPACE_MANAGER_NEXT_ID_TABLE_NAME +" FOR UPDATE ";

        private static final long NEXT_LONG_STEP=10000;

        private static final String increaseNextId =
                "UPDATE "+ManagerSchemaConstants.SPACE_MANAGER_NEXT_ID_TABLE_NAME +
                " SET NextToken=NextToken+"+NEXT_LONG_STEP;
        private long nextLongBase;
        private long _nextLongBase;
        private long nextLongIncrement=NEXT_LONG_STEP; //trigure going into database on startup

        private synchronized  long getNextToken(Connection connection)
        {
                if(nextLongIncrement >= NEXT_LONG_STEP) {
                        nextLongIncrement =0;
                        try {
                                incrementNextLongBase(connection);
                        }
                        catch(SQLException e) {
                                logger.error("incrementNextLongBase failed: {}",
                                        e.getMessage());
                                if (connection!=null) {
                                        try {
                                                connection.rollback();
                                        }
                                        catch(Exception e1) { }
                                }
                                nextLongBase = _nextLongBase;
                        }
                        _nextLongBase = nextLongBase+ NEXT_LONG_STEP;
                }

                long nextLong = nextLongBase +(nextLongIncrement++);
                logger.trace("return nextLong={}", nextLong);
                return nextLong;
        }

        private void incrementNextLongBase(Connection connection) throws SQLException{
                PreparedStatement s = connection.prepareStatement(selectNextIdForUpdate);
                logger.trace("getNextToken trying {}", selectNextIdForUpdate);
                ResultSet set = s.executeQuery();
                if(!set.next()) {
                        s.close();
                        throw new SQLException("table "+ManagerSchemaConstants.SPACE_MANAGER_NEXT_ID_TABLE_NAME +" is empty!!!", "02000");
                }
                nextLongBase = set.getLong(1);
                s.close();
                logger.trace("nextLongBase is = {}", nextLongBase);
                s = connection.prepareStatement(increaseNextId);
                logger.trace("executing statement: {}", increaseNextId);
                s.executeUpdate();
                s.close();
                connection.commit();
        }

//
// unchanged code ends here
//
        private static final String selectLinkGroupVOs =
                "SELECT VOGroup,VORole FROM "+ManagerSchemaConstants.LINK_GROUP_VOS_TABLE_NAME +
                " WHERE linkGroupId=?";

        private static final String onlineSelectionCondition =
                "lg.onlineallowed = 1 ";
        private static final String nearlineSelectionCondition =
                "lg.nearlineallowed = 1 ";
        private static final String replicaSelectionCondition =
                "lg.replicaallowed = 1 ";
        private static final String outputSelectionCondition =
                "lg.outputallowed = 1 ";
        private static final String custodialSelectionCondition =
                "lg.custodialAllowed = 1 ";

        private static final String voGroupSelectionCondition =
                " ( lgvo.VOGroup = ? OR lgvo.VOGroup = '*' ) ";
        private static final String voRoleSelectionCondition =
                " ( lgvo.VORole = ? OR lgvo.VORole = '*' ) ";

        private static final String spaceCondition=
                " lg.freespaceinbytes-lg.reservedspaceinbytes >= ? ";
        private static final String orderBy=
                " order by available desc ";

        private static final String selectLinkGroupInfoPart1 = "SELECT lg.*,"+
                "lg.freespaceinbytes-lg.reservedspaceinbytes as available "+
                "\n from srmlinkgroup lg, srmlinkgroupvos lgvo"+
                "\n where lg.id=lgvo.linkGroupId  and  lg.lastUpdateTime >= ? ";

        private static final String selectOnlineReplicaLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                replicaSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectOnlineOutputLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                outputSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectOnlineCustodialLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                custodialSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectNearlineReplicaLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                replicaSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectNearlineOutputLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                outputSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;


        private static final String selectNearlineCustodialLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                custodialSelectionCondition + " and "+
                voGroupSelectionCondition + " and "+
                voRoleSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectAllOnlineReplicaLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                replicaSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectAllOnlineOutputLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                outputSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectAllOnlineCustodialLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                onlineSelectionCondition + " and "+
                custodialSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectAllNearlineReplicaLinkGroup  =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                replicaSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        private static final String selectAllNearlineOutputLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                outputSelectionCondition + " and "+
                spaceCondition +
                orderBy;


        private static final String selectAllNearlineCustodialLinkGroup =
                selectLinkGroupInfoPart1+" and "+
                nearlineSelectionCondition + " and "+
                custodialSelectionCondition + " and "+
                spaceCondition +
                orderBy;

        //
        // the function below returns list of linkgroup ids that correspond
        // to linkgroups that satisfy retention policy/access latency criteria,
        // voGroup/voRoles criteria and have sufficient space to accommodate new
        // space reservation. Sufficient space is defined as lg.freespaceinbytes-lg.reservedspaceinbytes
        // we do not use select for update here as we do not want to lock many
        // rows.

        private Long[] findLinkGroupIds(long sizeInBytes,
                                        String voGroup,
                                        String voRole,
                                        AccessLatency al,
                                        RetentionPolicy rp)
                throws SQLException {
                try {
                        logger.trace("findLinkGroupIds(sizeInBytes={}, " +
                                "voGroup={} voRole={}, AccessLatency={}, " +
                                "RetentionPolicy={})", sizeInBytes, voGroup,
                                voRole, al, rp);
                        String select;
                        if(al.equals(AccessLatency.ONLINE)) {
                                if(rp.equals(RetentionPolicy.REPLICA)) {
                                        select = selectOnlineReplicaLinkGroup;
                                }
                                else
                                        if ( rp.equals(RetentionPolicy.OUTPUT)) {
                                                select = selectOnlineOutputLinkGroup;
                                        }
                                        else {
                                                select = selectOnlineCustodialLinkGroup;
                                        }

                        }
                        else {
                                if(rp.equals(RetentionPolicy.REPLICA)) {
                                        select = selectNearlineReplicaLinkGroup;
                                }
                                else
                                        if ( rp.equals(RetentionPolicy.OUTPUT)) {
                                                select = selectNearlineOutputLinkGroup;
                                        }
                                        else {
                                                select = selectNearlineCustodialLinkGroup;
                                        }
                        }
                        logger.trace("executing statement: {}?={}?={}?={}?={}",
                                select, latestLinkGroupUpdateTime, voGroup,
                                voRole, sizeInBytes);
                        Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                                     select,
                                                                     latestLinkGroupUpdateTime,
                                                                     voGroup,
                                                                     voRole,
                                                                     sizeInBytes);
                        List<Long> idlist = new ArrayList<>();
                        for(LinkGroup group : groups) {
                                idlist.add(group.getId());
                        }
                        return idlist.toArray(new Long[idlist.size()]);
                }
                catch(SQLException sqle) {
                    logger.error("select failed: {}", sqle.getMessage());
                    throw sqle;
                }
        }

        private Set<LinkGroup> findLinkGroupIds(long sizeInBytes,
                                                AccessLatency al,
                                                RetentionPolicy rp) throws SQLException {
                try {
                        logger.trace("findLinkGroupIds(sizeInBytes={}, " +
                                "AccessLatency={}, RetentionPolicy={})",
                                sizeInBytes, al, rp);
                        String select;
                        if(al.equals(AccessLatency.ONLINE)) {
                                if(rp.equals(RetentionPolicy.REPLICA)) {
                                        select = selectAllOnlineReplicaLinkGroup;
                                }
                                else
                                        if ( rp.equals(RetentionPolicy.OUTPUT)) {
                                                select = selectAllOnlineOutputLinkGroup;
                                        }
                                        else {
                                                select = selectAllOnlineCustodialLinkGroup;
                                        }

                        }
                        else {
                                if(rp.equals(RetentionPolicy.REPLICA)) {
                                        select = selectAllNearlineReplicaLinkGroup;
                                }
                                else
                                        if ( rp.equals(RetentionPolicy.OUTPUT)) {
                                                select = selectAllNearlineOutputLinkGroup;
                                        }
                                        else {
                                                select = selectAllNearlineCustodialLinkGroup;
                                        }
                        }
                        logger.trace("executing statement: {} ?={}?={}",
                                select, latestLinkGroupUpdateTime, sizeInBytes);
                        Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                                     select,
                                                                     latestLinkGroupUpdateTime,
                                                                     sizeInBytes);
                        return groups;
                }
                catch(SQLException sqle) {
                        logger.error("select failed: {}", sqle.getMessage());
                        throw sqle;
                }
        }

        private Space getSpace(long id)  throws SQLException{
                logger.trace("Executing: {},?={}", SpaceReservationIO.
                        SELECT_SPACE_RESERVATION_BY_ID, id);
                Set<Space> spaces=dbManager.selectPrepared(spaceReservationIO,
                                                         SpaceReservationIO.SELECT_SPACE_RESERVATION_BY_ID,
                                                         id);
                if (spaces.isEmpty()) {
                        throw new SQLException("Space reservation " + id + " not found.", "02000");
                }
                return Iterables.getFirst(spaces,null);
        }

        private LinkGroup getLinkGroup(long id)  throws SQLException{
                Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                             LinkGroupIO.SELECT_LINKGROUP_BY_ID,
                                                             id);
                if (groups.isEmpty()) {
                        throw new SQLException("linkGroup with id="+id+" not found", "02000");
                }
                return Iterables.getFirst(groups,null);
        }

        private LinkGroup getLinkGroupByName(String name)  throws SQLException{
                Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                             LinkGroupIO.SELECT_LINKGROUP_BY_NAME,
                                                             name);
                if (groups.isEmpty()) {
                        throw new SQLException("linkGroup with name="+name+" not found", "02000");
                }
                return Iterables.getFirst(groups,null);
        }

//------------------------------------------------------------------------------
// select for update functions
//------------------------------------------------------------------------------
        @Nonnull
        private Space selectSpaceForUpdate(Connection connection, long id, long sizeInBytes)  throws SQLException{
                try {
                        return dbManager.selectForUpdate(connection,
                                                       spaceReservationIO,
                                                       SpaceReservationIO.SELECT_FOR_UPDATE_BY_ID_AND_SIZE,
                                                       id,
                                                       sizeInBytes);
                }
                catch (SQLException e) {
                    if (Objects.equals(e.getSQLState(), "02000")) {
                        throw new SQLException("There is no space reservation with id="+id+" and available size="+sizeInBytes,
                                e.getSQLState(), e.getErrorCode(), e);
                    }
                    throw e;
                }
        }

        @Nonnull
        private Space selectSpaceForUpdate(Connection connection, long id)  throws SQLException{
                try {
                        return dbManager.selectForUpdate(connection,
                                                       spaceReservationIO,
                                                       SpaceReservationIO.SELECT_FOR_UPDATE_BY_ID,
                                                       id);
                }
                catch (SQLException e){
                    if (Objects.equals(e.getSQLState(), "02000")) {
                        throw new SQLException("There is no space reservation with id="+id,
                                e.getSQLState(), e.getErrorCode(), e);
                    }
                    throw e;
                }
        }

        @Nonnull
        private File selectFileForUpdate(Connection connection,
                                         PnfsId pnfsId)
                throws SQLException {
                try {
                        return dbManager.selectForUpdate(connection,
                                                       fileIO,
                                                       FileIO.SELECT_FOR_UPDATE_BY_PNFSID,
                                                       pnfsId.toString());
                }
                catch (SQLException e){
                    if (Objects.equals(e.getSQLState(), "02000")) {
                        throw new SQLException("There is no file with pnfsid="+
                                               pnfsId, e.getSQLState(), e.getErrorCode(), e);
                    }
                    throw e;
                }
        }

        @Nonnull
        private File selectFileForUpdate(Connection connection,
                                         long id)
                throws SQLException {
                try {
                        return dbManager.selectForUpdate(connection,
                                                       fileIO,
                                                       FileIO.SELECT_FOR_UPDATE_BY_ID,
                                                       id);
                }
                catch (SQLException e){
                    if (Objects.equals(e.getSQLState(), "02000")) {
                        throw new SQLException("There is no file with id="+id,
                                e.getSQLState(), e.getErrorCode(), e);
                    }
                    throw e;
                }
        }

        @Nonnull
        private File selectFileFromSpaceForUpdate(Connection connection,
                                                  String pnfsPath,
                                                  long reservationId)

                throws SQLException {
                return dbManager.selectForUpdate(connection,
                                               fileIO,
                                               FileIO.SELECT_TRANSIENT_FILES_BY_PNFSPATH_AND_RESERVATIONID,
                                               pnfsPath, reservationId);
        }


        private void removeFileFromSpace(long id) throws SQLException {
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        removeFileFromSpace(connection, id);
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                }
                catch(SQLException sqle) {
                        logger.error("delete failed: {}", sqle.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        public void removeFileFromSpace(Connection connection, long fileId) throws SQLException {
                int rc = dbManager.delete(connection,FileIO.DELETE, fileId);
                if(rc!=1){
                        throw new SQLException("delete returned row count ="+rc);
                }
        }


//------------------------------------------------------------------------------
        private Space updateSpaceState(long id, SpaceState spaceState) throws SQLException {
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        Space space = updateSpaceReservation(connection,
                                               id,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               null,
                                               spaceState);
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                        return space;
                }
                catch(SQLException sqle) {
                        logger.error("update failed: {}", sqle.getMessage());
                        if(connection != null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private Space updateSpaceReservation(Connection connection,
                                            long id,
                                            String voGroup,
                                            String voRole,
                                            RetentionPolicy retentionPolicy,
                                            AccessLatency accessLatency,
                                            Long linkGroupId,
                                            Long sizeInBytes,
                                            Long lifetime,
                                            String description,
                                            SpaceState state) throws SQLException {

                Space space = selectSpaceForUpdate(connection,id);
                updateSpaceReservation(connection,
                                       voGroup,
                                       voRole,
                                       retentionPolicy,
                                       accessLatency,
                                       linkGroupId,
                                       sizeInBytes,
                                       lifetime,
                                       description,
                                       state,
                                       space);
                return space;
        }

        private void updateSpaceReservation(Connection connection,
                                            String voGroup,
                                            String voRole,
                                            RetentionPolicy retentionPolicy,
                                            AccessLatency accessLatency,
                                            Long linkGroupId,
                                            Long sizeInBytes,
                                            Long lifetime,
                                            String description,
                                            SpaceState state,
                                            Space space) throws SQLException {
                if (voGroup!=null) {
                    space.setVoGroup(voGroup);
                }
                if (voRole!=null) {
                    space.setVoRole(voRole);
                }
                if (retentionPolicy!=null) {
                    space.setRetentionPolicy(retentionPolicy);
                }
                if (accessLatency!=null) {
                    space.setAccessLatency(accessLatency);
                }
                if (sizeInBytes != null)  {
                        long usedSpace = space.getUsedSizeInBytes()+space.getAllocatedSpaceInBytes();
                        if (sizeInBytes < usedSpace) {
                                throw new SQLException("Cannot downsize space reservation below "+usedSpace+"bytes, remove files first ");
                        }
                        space.setSizeInBytes(sizeInBytes);
                }
                if(lifetime!=null) {
                    space.setLifetime(lifetime);
                }
                if(description!= null) {
                    space.setDescription(description);
                }
                SpaceState oldState = space.getState();
                if(state != null)  {
                        if (SpaceState.isFinalState(oldState)) {
                                throw new SQLException("change from "+oldState+" to "+state+" is not allowed");
                        }
                        space.setState(state);
                }
                dbManager.update(connection,
                               SpaceReservationIO.UPDATE,
                               space.getVoGroup(),
                               space.getVoRole(),
                               space.getRetentionPolicy().getId(),
                               space.getAccessLatency().getId(),
                               space.getLinkGroupId(),
                               space.getSizeInBytes(),
                               space.getCreationTime(),
                               space.getLifetime(),
                               space.getDescription(),
                               space.getState().getStateId(),
                               space.getId());
        }

        private Space updateSpaceReservation(long id,
                                            String voGroup,
                                            String voRole,
                                            RetentionPolicy retentionPolicy,
                                            AccessLatency accessLatency,
                                            Long linkGroupId,
                                            Long sizeInBytes,
                                            Long lifetime,
                                            String description,
                                            SpaceState state) throws SQLException {
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        Space space = updateSpaceReservation(connection,
                                               id,
                                               voGroup,
                                               voRole,
                                               retentionPolicy,
                                               accessLatency,
                                               linkGroupId,
                                               sizeInBytes,
                                               lifetime,
                                               description,
                                               state);
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                        return space;
                }
                catch(SQLException sqle) {
                        logger.error("update failed: {}", sqle.getMessage());
                        if(connection != null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private void expireSpaceReservations()  {
                logger.trace("expireSpaceReservations()...");
                try {
                        if (cleanupExpiredSpaceFiles) {
                                long time = System.currentTimeMillis();
                                Set<Space> spaces = dbManager.selectPrepared(spaceReservationIO,
                                                                           SpaceReservationIO.SELECT_SPACE_RESERVATIONS_FOR_EXPIRED_FILES,
                                                                           time);
                                for (Space space : spaces) {
                                        //
                                        // for each space make a list of files in this space and clean them up
                                        //
                                        Set<File> files = dbManager.selectPrepared(fileIO,
                                                                                 FileIO.SELECT_EXPIRED_SPACEFILES,
                                                                                 System.currentTimeMillis(),
                                                                                 space.getId());
                                        for (File file : files) {
                                                try {
                                                        if (file.getPnfsId() != null) {
                                                                try {
                                                                        pnfs.deletePnfsEntry(file.getPnfsId(), file.getPnfsPath());
                                                                } catch (FileNotFoundCacheException ignored) {
                                                                }
                                                        }
                                                        removeFileFromSpace(file.getId());
                                                }
                                                catch (SQLException e) {
                                                        logger.error("Failed to remove file {}: {}",
                                                                file, e.getMessage());
                                                }
                                                catch (CacheException e) {
                                                        logger.error("Failed to delete file {}: {}",
                                                                file.getPnfsId(), e.getMessage());
                                                }
                                        }
                                }
                        }
                        logger.trace("Executing: {}",
                                SpaceReservationIO.SELECT_EXPIRED_SPACE_RESERVATIONS1);
                        Set<Space> spaces = dbManager.selectPrepared(spaceReservationIO,
                                                                   SpaceReservationIO.SELECT_EXPIRED_SPACE_RESERVATIONS1,
                                                                   System.currentTimeMillis());
                        for (Space space : spaces ) {
                                try {
                                        updateSpaceReservation(space.getId(),
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               SpaceState.EXPIRED);
                                }
                                catch (SQLException e) {
                                        logger.error("failed to remove expired " +
                                                "reservation {}: {}", space,
                                                e.getMessage());
                                }
                        }
                }
                catch(SQLException sqle) {
                        logger.error("expireSpaceReservations failed: {}",
                                sqle.getMessage());
                }
        }

        private void insertSpaceReservation(Connection connection,
                                            long id,
                                            String voGroup,
                                            String voRole,
                                            RetentionPolicy retentionPolicy,
                                            AccessLatency accessLatency,
                                            long linkGroupId,
                                            long sizeInBytes,
                                            long lifetime,
                                            String description,
                                            int state,
                                            long used,
                                            long allocated) throws SQLException {
                long creationTime=System.currentTimeMillis();
                int rc=dbManager.insert(connection,
                                      SpaceReservationIO.INSERT,
                                      id,
                                      voGroup,
                                      voRole,
                                      retentionPolicy==null? 0 : retentionPolicy.getId(),
                                      accessLatency==null? 0 : accessLatency.getId(),
                                      linkGroupId,
                                      sizeInBytes,
                                      creationTime,
                                      lifetime,
                                      description,
                                      state,
                                      used,
                                      allocated);
                if (rc!=1) {
                        throw new SQLException("insert returned row count ="+rc);
                }
        }

        //
        // functions for infoProvider
        //

        private void getValidSpaceTokens(GetSpaceTokensMessage msg) throws SQLException {
                Set<Space> spaces;
                if(msg.getSpaceTokenId()!=null) {
                        spaces = Collections.singleton(getSpace(msg.getSpaceTokenId()));
                }
                else {
                        spaces=dbManager.selectPrepared(spaceReservationIO,
                                                      SpaceReservationIO.SELECT_CURRENT_SPACE_RESERVATIONS);
                }
                msg.setSpaceTokenSet(spaces);
        }


        private void getValidSpaceTokenIds(GetSpaceTokenIdsMessage msg) throws SQLException {
                Set<Space> spaces=dbManager.selectPrepared(spaceReservationIO,
                                                         SpaceReservationIO.SELECT_CURRENT_SPACE_RESERVATIONS);

                long[] ids = new long[spaces.size()];
                int j=0;
                for (Space space : spaces) {
                        ids[j++]=space.getId();
                }
                msg.setSpaceTokenIds(ids);
        }

        private void getLinkGroups(GetLinkGroupsMessage msg) throws SQLException {
                Set<LinkGroup> groups;
                if (msg.getLinkgroupidId()!=null) {
                        groups = Collections.singleton(getLinkGroup(msg.getLinkgroupidId()));
                }
                else {
                        groups=dbManager.selectPrepared(linkGroupIO,
                                                      LinkGroupIO.SELECT_ALL_LINKGROUPS);
                }
                msg.setLinkGroupSet(groups);
        }

        private void getLinkGroupNames(GetLinkGroupNamesMessage msg) throws SQLException {
                Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                             LinkGroupIO.SELECT_ALL_LINKGROUPS);
                String[] names = new String[groups.size()];
                int j=0;
                for (LinkGroup group : groups) {
                        names[j++]=group.getName();
                }
                msg.setLinkGroupNames(names);
        }

        private void getLinkGroupIds(GetLinkGroupIdsMessage msg) throws SQLException {
                Set<LinkGroup> groups=dbManager.selectPrepared(linkGroupIO,
                                                             LinkGroupIO.SELECT_ALL_LINKGROUPS);
                long[] ids = new long[groups.size()];
                int j=0;
                for (LinkGroup group : groups) {
                        ids[j++]=group.getId();
                }
                msg.setLinkGroupIds(ids);
        }

        private static final String SELECT_SPACE_TOKENS_BY_DESCRIPTION =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_TABLE_NAME +
                " WHERE  state = ? AND description = ?";

        private static final String SELECT_SPACE_TOKENS_BY_VOGROUP =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_TABLE_NAME +
                " WHERE  state = ? AND voGroup = ?";

        private static final String SELECT_SPACE_TOKENS_BY_VOROLE =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_TABLE_NAME +
                " WHERE  state = ? AND  voRole = ?";

        private static final String SELECT_SPACE_TOKENS_BY_VOGROUP_AND_VOROLE =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_TABLE_NAME +
                " WHERE  state = ? AND voGroup = ? AND voRole = ?";

        private Set<Space> findSpacesByVoGroupAndRole(String voGroup, String voRole)
                throws SQLException {
                if (voGroup!=null&&!voGroup.isEmpty()&&
                    voRole!=null&&!voRole.isEmpty()) {
                        return dbManager.selectPrepared(spaceReservationIO,
                                                      SELECT_SPACE_TOKENS_BY_VOGROUP_AND_VOROLE,
                                                      SpaceState.RESERVED.getStateId(),
                                                      voGroup,
                                                      voRole);
                }
                if (voGroup!=null&&!voGroup.isEmpty()) {
                        return dbManager.selectPrepared(spaceReservationIO,
                                                      SELECT_SPACE_TOKENS_BY_VOGROUP,
                                                      SpaceState.RESERVED.getStateId(),
                                                      voGroup);
                }
                if (voRole!=null&&!voRole.isEmpty()) {
                        return dbManager.selectPrepared(spaceReservationIO,
                                                      SELECT_SPACE_TOKENS_BY_VOROLE,
                                                      SpaceState.RESERVED.getStateId(),
                                                      voRole);
                }
                return Collections.emptySet();
        }

        private long[] getSpaceTokens(Subject subject,
                                      String description) throws SQLException {

                Set<Space> spaces = new HashSet<>();
                if (description==null) {
                    for (String s : Subjects.getFqans(subject)) {
                        if (s != null) {
                            FQAN fqan = new FQAN(s);
                            spaces.addAll(findSpacesByVoGroupAndRole(fqan.getGroup(), fqan.getRole()));
                        }
                    }
                    spaces.addAll(findSpacesByVoGroupAndRole(Subjects.getUserName(subject), ""));
                }
                else {
                        Set<Space> foundSpaces=dbManager.selectPrepared(
                                spaceReservationIO,
                                SELECT_SPACE_TOKENS_BY_DESCRIPTION,
                                SpaceState.RESERVED.getStateId(),
                                description);
                        if (foundSpaces!=null) {
                                spaces.addAll(foundSpaces);
                        }
                }
                long[] tokens=new long[spaces.size()];
                int i=0;
                for (Space space : spaces) {
                        tokens[i++] = space.getId();
                }
                return tokens;
        }

        private static final String SELECT_SPACE_FILE_BY_PNFSID =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_FILE_TABLE_NAME +
                " WHERE pnfsId = ? ";

        private static final String SELECT_SPACE_FILE_BY_PNFSPATH =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_FILE_TABLE_NAME +
                " WHERE pnfsPath = ? ";

        private static final String SELECT_SPACE_FILE_BY_PNFSID_AND_PNFSPATH =
                "SELECT * FROM "+ManagerSchemaConstants.SPACE_FILE_TABLE_NAME +
                " WHERE pnfsId = ? AND pnfsPath = ?";

        @Nonnull
        private long[] getFileSpaceTokens(PnfsId pnfsId,
                                          String pnfsPath)  throws SQLException{

                if (pnfsId==null&&pnfsPath==null) {
                        throw new IllegalArgumentException("getFileSpaceTokens: all arguments are nulls, not supported");
                }
                Set<File> files = null;
                if (pnfsId != null && pnfsPath != null) {
                        files = dbManager.selectPrepared(fileIO,
                                                       SELECT_SPACE_FILE_BY_PNFSID_AND_PNFSPATH,
                                                       pnfsId.toString(),
                                                       new FsPath(pnfsPath).toString());
                }
                else {
                        if (pnfsId != null) {
                                files = dbManager.selectPrepared(fileIO,
                                                               SELECT_SPACE_FILE_BY_PNFSID,
                                                               pnfsId.toString());
                        }
                        if (pnfsPath != null) {
                                files = dbManager.selectPrepared(fileIO,
                                                               SELECT_SPACE_FILE_BY_PNFSPATH,
                                                               new FsPath(pnfsPath).toString());
                        }
                }
                long[] tokens = new long[files.size()];
                int i=0;
                for (File file : files) {
                        tokens[i++] = file.getSpaceId();
                }
                return tokens;
        }

        private void updateSpaceFile(Connection connection,
                                     String voGroup,
                                     String voRole,
                                     PnfsId pnfsId,
                                     Long sizeInBytes,
                                     Long lifetime,
                                     Integer state,
                                     File f) throws SQLException {
                if (voGroup!=null) {
                        f.setVoGroup(voGroup);
                }
                if (voRole!=null) {
                        f.setVoRole(voRole);
                }
                if (sizeInBytes != null) {
                    f.setSizeInBytes(sizeInBytes);
                }
                if (lifetime!=null) {
                    f.setLifetime(lifetime);
                }
                if (state!=null)   {
                        f.setState(FileState.getState(state));
                }
                if (pnfsId!=null ) {
                    f.setPnfsId(pnfsId);
                }
                int rc = dbManager.update(connection,
                                        FileIO.UPDATE,
                                        f.getVoGroup(),
                                        f.getVoRole(),
                                        f.getSizeInBytes(),
                                        f.getLifetime(),
                                        Objects.toString(f.getPnfsId()),
                                        f.getState().getStateId(),
                                        f.getId());
                if (rc!=1) {
                        throw new SQLException("Update failed, row count="+rc);
                }
        }

        private void setPnfsIdOfFileInSpace(long id, PnfsId pnfsId) throws SQLException {
            Connection connection=null;
            try {
                connection=connection_pool.getConnection();
                connection.setAutoCommit(false);
                File f = selectFileForUpdate(connection, id);
                if (f.getPnfsId() != null) {
                    throw new SQLException("File is already assigned a PNFS ID.");
                }
                updateSpaceFile(connection, null, null, pnfsId,
                        null, null, null, f);
                connection.commit();
                connection_pool.returnConnection(connection);
                connection=null;
            }
            catch (SQLException sqle) {
                logger.error("update failed: {}", sqle.getMessage());
                if (connection!=null) {
                    connection.rollback();
                    connection_pool.returnFailedConnection(connection);
                    connection=null;
                }
                throw sqle;
            }
            finally {
                if (connection!=null) {
                    connection_pool.returnConnection(connection);
                }
            }
        }

        private void removePnfsIdOfFileInSpace(Connection connection, long id)
                throws SQLException {
            dbManager.update(connection, FileIO.REMOVE_PNFSID_ON_SPACEFILE, id);
        }

        private void removePnfsIdAndChangeStateOfFileInSpace(Connection connection, long id, int state)
                throws SQLException {
            dbManager.update(connection, FileIO.REMOVE_PNFSID_AND_CHANGE_STATE_SPACEFILE, state, id);
        }

        public void insertFileInSpace(Connection connection,
                                      long id,
                                      String voGroup,
                                      String voRole,
                                      long spaceReservationId,
                                      long sizeInBytes,
                                      long lifetime,
                                      FsPath pnfsPath,
                                      PnfsId pnfsId,
                                      int state) throws SQLException,
                                                        SpaceException {
                long creationTime=System.currentTimeMillis();
                Space space = selectSpaceForUpdate(connection,spaceReservationId,0L); // "0L" is a hack needed to get a better error code from comparison below
                long currentTime = System.currentTimeMillis();
                if(space.getLifetime() != -1 && space.getCreationTime()+space.getLifetime()  < currentTime) {
                        throw new SpaceExpiredException("space with id="+
                                                        spaceReservationId+
                                                        " has expired");
                }
                if (space.getState() == SpaceState.EXPIRED) {
                        throw new SpaceExpiredException("space with id="+
                                                        spaceReservationId+
                                                        " has expired");
                }
                if (space.getState() == SpaceState.RELEASED) {
                        throw new SpaceReleasedException("space with id="+
                                                         spaceReservationId+
                                                         " was released");
                }
                if (space.getAvailableSpaceInBytes()<sizeInBytes) {
                        throw new NoFreeSpaceException("space with id="+
                                                       spaceReservationId+
                                                       " does not have enough space");
                }
                int rc = dbManager.insert(connection,
                                          FileIO.INSERT_W_PNFSID,
                                          id,
                                          voGroup,
                                          voRole,
                                          spaceReservationId,
                                          sizeInBytes,
                                          creationTime,
                                          lifetime,
                                          Objects.toString(pnfsPath),
                                          Objects.toString(pnfsId),
                                          state);
                if(rc!=1 ){
                        throw new SQLException("insert returned row count ="+rc);
                }
        }

        private File getFile(PnfsId pnfsId)  throws SQLException {
                Set<File> files=dbManager.selectPrepared(fileIO,
                                                       FileIO.SELECT_BY_PNFSID,
                                                       pnfsId.toString());
                if (files.isEmpty()) {
                        throw new SQLException("file with pnfsId="+pnfsId+
                                                " is not found", "02000");
                }
                if (files.size()>1) {
                        throw new SQLException("found two records with pnfsId="+
                                               pnfsId);
                }
                return Iterables.getFirst(files,null);
        }

        /** Returns true if message is of a type processed exclusively by SpaceManager */
        private boolean isSpaceManagerMessage(Message message)
        {
                return message instanceof Reserve
                        || message instanceof GetSpaceTokensMessage
                        || message instanceof GetSpaceTokenIdsMessage
                        || message instanceof GetLinkGroupsMessage
                        || message instanceof GetLinkGroupNamesMessage
                        || message instanceof GetLinkGroupIdsMessage
                        || message instanceof Release
                        || message instanceof Use
                        || message instanceof CancelUse
                        || message instanceof GetSpaceMetaData
                        || message instanceof GetSpaceTokens
                        || message instanceof ExtendLifetime
                        || message instanceof GetFileSpaceTokensMessage;
        }

        /** Returns true if message is a notification to which SpaceManager subscribes */
        private boolean isNotificationMessage(Message message)
        {
                return message instanceof PoolFileFlushedMessage
                        || message instanceof PoolRemoveFilesMessage
                        || message instanceof PnfsDeleteEntryNotificationMessage;
        }

        /**
         * Returns true if message is of a type that needs processing by SpaceManager even if
         * SpaceManager is not the intended final destination.
         */
        private boolean isInterceptedMessage(Message message)
        {
                return (message instanceof PoolMgrSelectWritePoolMsg && ((PoolMgrSelectWritePoolMsg) message).getPnfsPath() != null && !message.isReply())
                       || message instanceof DoorTransferFinishedMessage
                       || (message instanceof PoolAcceptFileMessage && ((PoolAcceptFileMessage) message).getFileAttributes().getStorageInfo().getKey("LinkGroup") != null);
        }

        public void messageArrived(final CellMessage envelope,
                                   final Message message)
        {
            logger.trace("messageArrived : type={} value={} from {}",
                         message.getClass().getName(), message, envelope.getSourcePath());

            if (!message.isReply()) {
                if (!isNotificationMessage(message) && !isSpaceManagerMessage(message)) {
                    messageToForward(envelope, message);
                } else if (spaceManagerEnabled) {
                    executor.execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            processMessage(message);
                            if (message.getReplyRequired()) {
                                try {
                                    envelope.revertDirection();
                                    sendMessage(envelope);
                                }
                                catch (NoRouteToCellException e) {
                                    logger.error("Failed to send reply: {}", e.getMessage());
                                }
                            }
                        }
                    });
                } else if (message.getReplyRequired()) {
                    try {
                        message.setReply(1, "Space manager is disabled in configuration");
                        envelope.revertDirection();
                        sendMessage(envelope);
                    }
                    catch (NoRouteToCellException e) {
                        logger.error("Failed to send reply: {}", e.getMessage());
                    }
                }
            }
        }

        public void messageToForward(final CellMessage envelope, final Message message)
        {
            logger.trace("messageToForward: type={} value={} from {} going to {}",
                         message.getClass().getName(),
                         message,
                         envelope.getSourcePath(),
                         envelope.getDestinationPath());

            final boolean isEnRouteToDoor = message.isReply() || message instanceof DoorTransferFinishedMessage;
            if (!isEnRouteToDoor) {
                envelope.getDestinationPath().insert(poolManager);
            }

            if (envelope.nextDestination()) {
                if (spaceManagerEnabled && isInterceptedMessage(message)) {
                    executor.execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            processMessage(message);

                            if (message.getReturnCode() != 0 && !isEnRouteToDoor) {
                                envelope.revertDirection();
                            }

                            try {
                                sendMessage(envelope);
                            } catch (NoRouteToCellException e) {
                                logger.error("Failed to forward message: {}", e.getMessage());
                            }
                        }
                    });
                } else {
                    try {
                        sendMessage(envelope);
                    } catch (NoRouteToCellException e) {
                        logger.error("Failed to forward message: {}", e.getMessage());
                    }
                }
            }
        }

        private void processMessage(Message message)
        {
            try {
                if (message instanceof PoolRemoveFilesMessage) {
                    fileRemoved((PoolRemoveFilesMessage) message);
                }
                else if (message instanceof PoolMgrSelectWritePoolMsg) {
                    selectPool((PoolMgrSelectWritePoolMsg) message);
                }
                else if (message instanceof PoolAcceptFileMessage) {
                    PoolAcceptFileMessage poolRequest = (PoolAcceptFileMessage) message;
                    if (message.isReply()) {
                        transferStarted(poolRequest.getPnfsId(), poolRequest.getReturnCode() == 0);
                    }
                    else {
                        transferStarting(poolRequest);
                    }
                }
                else if (message instanceof DoorTransferFinishedMessage) {
                    transferFinished((DoorTransferFinishedMessage) message);
                }
                else if (message instanceof Reserve) {
                    reserveSpace((Reserve) message);
                }
                else if (message instanceof GetSpaceTokensMessage) {
                    getValidSpaceTokens((GetSpaceTokensMessage) message);
                }
                else if (message instanceof GetLinkGroupsMessage) {
                    getLinkGroups((GetLinkGroupsMessage) message);
                }
                else if (message instanceof GetLinkGroupNamesMessage) {
                    getLinkGroupNames((GetLinkGroupNamesMessage) message);
                }
                else if (message instanceof Release) {
                    releaseSpace((Release) message);
                }
                else if (message instanceof Use) {
                    useSpace((Use) message);
                }
                else if (message instanceof CancelUse) {
                    cancelUseSpace((CancelUse) message);
                }
                else if (message instanceof GetSpaceMetaData) {
                    getSpaceMetaData((GetSpaceMetaData) message);
                }
                else if (message instanceof GetSpaceTokens) {
                    getSpaceTokens((GetSpaceTokens) message);
                }
                else if (message instanceof ExtendLifetime) {
                    extendLifetime((ExtendLifetime) message);
                }
                else if (message instanceof PoolFileFlushedMessage) {
                    fileFlushed((PoolFileFlushedMessage) message);
                }
                else if (message instanceof GetFileSpaceTokensMessage) {
                    getFileSpaceTokens((GetFileSpaceTokensMessage) message);
                }
                else if (message instanceof PnfsDeleteEntryNotificationMessage) {
                    namespaceEntryDeleted((PnfsDeleteEntryNotificationMessage) message);
                }
                else {
                    throw new RuntimeException(
                            "Unexpected " + message.getClass() + ": Please report this to support@dcache.org");
                }
            } catch (SpaceAuthorizationException e) {
                message.setFailedConditionally(CacheException.PERMISSION_DENIED, e.getMessage());
            } catch (NoFreeSpaceException e) {
                message.setFailedConditionally(CacheException.RESOURCE, e.getMessage());
            } catch (SpaceException e) {
                message.setFailedConditionally(CacheException.DEFAULT_ERROR_CODE, e.getMessage());
            } catch (IllegalArgumentException e) {
                logger.error("forwarding msg failed: {}", e.getMessage(), e);
                message.setFailedConditionally(CacheException.INVALID_ARGS, e.getMessage());
            } catch (SQLException e) {
                logger.error("forwarding msg failed: {}", e.getMessage(), e);
                message.setFailedConditionally(CacheException.UNEXPECTED_SYSTEM_EXCEPTION, e.getMessage());
            } catch (RuntimeException e) {
                logger.error("forwarding msg failed: {}", e.getMessage(), e);
                message.setFailedConditionally(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                               "Internal failure during space management");
            }
        }

        private final Object updateLinkGroupsSyncObject = new Object();
        @Override
        public void run(){
                if(Thread.currentThread() == expireSpaceReservations) {
                        while(true) {
                                expireSpaceReservations();
                                try{
                                        Thread.sleep(expireSpaceReservationsPeriod);
                                }
                                catch (InterruptedException ie) {
                                        logger.trace("expire SpaceReservations thread has been interrupted");
                                        return;
                                }
                        }
                }
                else if(Thread.currentThread() == updateLinkGroups) {
                        while(true) {
                                updateLinkGroups();
                                synchronized(updateLinkGroupsSyncObject) {
                                        try {
                                                updateLinkGroupsSyncObject.wait(currentUpdateLinkGroupsPeriod);
                                        }
                                        catch (InterruptedException ie) {
                                                logger.trace("update LinkGroup thread has been interrupted");
                                                return;
                                        }
                                }
                        }
                }
        }

        private long latestLinkGroupUpdateTime =System.currentTimeMillis();
        private LinkGroupAuthorizationFile linkGroupAuthorizationFile;
        private long linkGroupAuthorizationFileLastUpdateTimestampt;

        private void updateLinkGroupAuthorizationFile() {
                if(linkGroupAuthorizationFileName == null) {
                        return;
                }
                java.io.File f = new java.io.File (linkGroupAuthorizationFileName);
                if(!f.exists()) {
                        linkGroupAuthorizationFile = null;
                }
                long lastModified = f.lastModified();
                if (linkGroupAuthorizationFile==null||
                    lastModified>=linkGroupAuthorizationFileLastUpdateTimestampt) {
                        linkGroupAuthorizationFileLastUpdateTimestampt = lastModified;
                        try {
                                linkGroupAuthorizationFile =
                                        new LinkGroupAuthorizationFile(linkGroupAuthorizationFileName);
                        }
                        catch(Exception e) {
                                logger.error("failed to parse LinkGroup" +
                                        "AuthorizationFile: {}",
                                        e.getMessage());
                        }
                }
        }

        private void updateLinkGroups() {
                currentUpdateLinkGroupsPeriod = EAGER_LINKGROUP_UPDATE_PERIOD;
                long currentTime = System.currentTimeMillis();
                Collection<PoolLinkGroupInfo> linkGroupInfos = Utils.linkGroupInfos(poolMonitor.getPoolSelectionUnit(), poolMonitor.getCostModule()).values();
                if (linkGroupInfos.isEmpty()) {
                    return;
                }

                currentUpdateLinkGroupsPeriod = updateLinkGroupsPeriod;

                updateLinkGroupAuthorizationFile();
                for (PoolLinkGroupInfo info : linkGroupInfos) {
                        String linkGroupName = info.getName();
                        long avalSpaceInBytes = info.getAvailableSpaceInBytes();
                        VOInfo[] vos = null;
                        boolean onlineAllowed = info.isOnlineAllowed();
                        boolean nearlineAllowed = info.isNearlineAllowed();
                        boolean replicaAllowed = info.isReplicaAllowed();
                        boolean outputAllowed = info.isOutputAllowed();
                        boolean custodialAllowed = info.isCustodialAllowed();
                        if (linkGroupAuthorizationFile != null) {
                                LinkGroupAuthorizationRecord record =
                                        linkGroupAuthorizationFile
                                        .getLinkGroupAuthorizationRecord(linkGroupName);
                                if (record != null) {
                                        vos = record.getVOInfoArray();
                                }
                        }
                        try {
                                updateLinkGroup(linkGroupName,
                                                avalSpaceInBytes,
                                                currentTime,
                                                onlineAllowed,
                                                nearlineAllowed,
                                                replicaAllowed,
                                                outputAllowed,
                                                custodialAllowed,
                                                vos);
                        } catch (SQLException sqle) {
                                logger.error("update of linkGroup {} failed: {}",
                                             linkGroupName, sqle.getMessage());
                        }
                }
                latestLinkGroupUpdateTime = currentTime;
        }

        private static final String INSERT_LINKGROUP_VO =
                "INSERT INTO "+ManagerSchemaConstants.LINK_GROUP_VOS_TABLE_NAME +
                " ( VOGroup, VORole, linkGroupId ) VALUES ( ? , ? , ? )";

        private static final String DELETE_LINKGROUP_VO =
                "DELETE FROM "+ManagerSchemaConstants.LINK_GROUP_VOS_TABLE_NAME +
                " WHERE VOGroup  = ? AND VORole = ? AND linkGroupId = ? ";

        private long updateLinkGroup(String linkGroupName,
                                     long freeSpace,
                                     long updateTime,
                                     boolean onlineAllowed,
                                     boolean nearlineAllowed,
                                     boolean replicaAllowed,
                                     boolean outputAllowed,
                                     boolean custodialAllowed,
                                     VOInfo[] linkGroupVOs) throws SQLException {
                long id;
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        try {
                                LinkGroup group = dbManager.selectForUpdate(connection,
                                                                          linkGroupIO,
                                                                          LinkGroupIO.SELECT_LINKGROUP_FOR_UPDATE_BY_NAME,
                                                                          linkGroupName);
                                id=group.getId();
                        }
                        catch (SQLException e) {
                                logger.error("failed to update linkgroup {}: {}",
                                        linkGroupName, e.getMessage());
                                try {
                                        connection.rollback();
                                }
                                catch (SQLException ignore) {
                                }
                                id=getNextToken(connection);
                                try {
                                     dbManager.insert(connection,
                                                       LinkGroupIO.INSERT,
                                                       id,
                                                       linkGroupName,
                                                       freeSpace,
                                                       updateTime,
                                                       (onlineAllowed ?1:0),
                                                       (nearlineAllowed ?1:0),
                                                       (replicaAllowed ?1:0),
                                                       (outputAllowed ?1:0),
                                                       (custodialAllowed ?1:0),
                                                       0);
                                }
                                catch (SQLException e1) {
                                        logger.error("failed to insert linkgroup {}: {}",
                                                linkGroupName, e1.getMessage());
                                        if (connection!=null) {
                                                connection.rollback();
                                                connection_pool.returnFailedConnection(connection);
                                                connection = null;
                                        }
                                        throw e1;
                                }
                        }
                        dbManager.update(connection,
                                       LinkGroupIO.UPDATE,
                                       freeSpace,
                                       updateTime,
                                       (onlineAllowed ?1:0),
                                       (nearlineAllowed ?1:0),
                                       (replicaAllowed ?1:0),
                                       (outputAllowed ?1:0),
                                       (custodialAllowed ?1:0),
                                       id);
                        PreparedStatement sqlStatement2 =
                                connection.prepareStatement(selectLinkGroupVOs);
                        sqlStatement2.setLong(1,id);
                        ResultSet VOsSet = sqlStatement2.executeQuery();
                        Set<VOInfo> insertVOs = new HashSet<>();
                        if(linkGroupVOs != null) {
                                insertVOs.addAll(asList(linkGroupVOs));
                        }
                        Set<VOInfo> deleteVOs = new HashSet<>();
                        while(VOsSet.next()) {
                                String nextVOGroup =    VOsSet.getString(1);
                                String nextVORole =    VOsSet.getString(2);
                                VOInfo nextVO = new VOInfo(nextVOGroup,nextVORole);
                                if(insertVOs.contains(nextVO)){
                                        insertVOs.remove(nextVO);
                                }
                                else {
                                        deleteVOs.add(nextVO);
                                }
                        }
                        VOsSet.close();
                        sqlStatement2.close();
                        for(VOInfo nextVo :insertVOs ) {
                                dbManager.update(connection,
                                               INSERT_LINKGROUP_VO,
                                               nextVo.getVoGroup(),
                                               nextVo.getVoRole(),
                                               id);
                        }
                        for(VOInfo nextVo : deleteVOs ) {
                                dbManager.update(connection,
                                               DELETE_LINKGROUP_VO,
                                               nextVo.getVoGroup(),
                                               nextVo.getVoRole(),
                                               id);
                        }
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection=null;
                        return id;
                }
                catch(SQLException sqle) {
                        logger.error("update failed: {}", sqle.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private void releaseSpace(Release release) throws
                SQLException,SpaceException {
                logger.trace("releaseSpace({})", release);

                long spaceToken = release.getSpaceToken();
                Long spaceToReleaseInBytes = release.getReleaseSizeInBytes();
                Space space = getSpace(spaceToken);
                if (space.getState() == SpaceState.RELEASED) {
                    /* Stupid way to signal that it isn't found, but there is no other way at the moment. */
                    throw new SQLException("Space reservation " + spaceToken + " was already released.", "02000");
                }
                Subject subject =  release.getSubject();
                authorizationPolicy.checkReleasePermission(subject, space);
                if(spaceToReleaseInBytes == null) {
                        updateSpaceState(spaceToken,SpaceState.RELEASED);
                }
                else {
                        throw new UnsupportedOperationException("partial release is not supported yet");
                }
        }

        //
        // working on the core stuff:
        //

        private void reserveSpace(Reserve reserve)
                throws SQLException, SpaceException{

                if (reserve.getRetentionPolicy()==null) {
                        throw new IllegalArgumentException("reserveSpace : retentionPolicy=null is not supported");
                }

                long reservationId = reserveSpace(reserve.getSubject(),
                                                  reserve.getSizeInBytes(),
                                                  (reserve.getAccessLatency() == null ?
                                                          defaultAccessLatency : reserve.getAccessLatency()),
                                                  reserve.getRetentionPolicy(),
                                                  reserve.getLifetime(),
                                                  reserve.getDescription(),
                                                  null,
                                                  null,
                                                  null);
                reserve.setSpaceToken(reservationId);
        }

        private void useSpace(Use use)
                throws SQLException, SpaceException{
                logger.trace("useSpace({})", use);
                long reservationId = use.getSpaceToken();
                Subject subject = use.getSubject();
                long sizeInBytes = use.getSizeInBytes();
                String pnfsPath = use.getPnfsName();
                PnfsId pnfsId = use.getPnfsId();
                long lifetime = use.getLifetime();
                long fileId = useSpace(reservationId,
                                       subject,
                                       sizeInBytes,
                                       lifetime,
                                       pnfsPath,
                                       pnfsId);
                use.setFileId(fileId);
        }

        private void transferStarting(PoolAcceptFileMessage message) throws SQLException, SpaceException
        {
            logger.trace("transferStarting({})", message);
            PnfsId pnfsId = checkNotNull(message.getPnfsId());
            FileAttributes fileAttributes = message.getFileAttributes();
            Subject subject = message.getSubject();
            String linkGroupName = checkNotNull(fileAttributes.getStorageInfo().getKey("LinkGroup"));
            String spaceToken = fileAttributes.getStorageInfo().getKey("SpaceToken");
            String fileId = fileAttributes.getStorageInfo().setKey("SpaceFileId", null);
            if (fileId != null) {
                /* This takes care of records created by SRM before
                 * transfer has started
                 */
                setPnfsIdOfFileInSpace(Long.parseLong(fileId), pnfsId);
            } else if (spaceToken != null) {
                logger.trace("transferStarting: file is not " +
                        "found, found default space " +
                        "token, calling useSpace()");
                long lifetime = 1000 * 60 * 60;
                useSpace(Long.parseLong(spaceToken),
                        subject,
                        message.getPreallocated(),
                        lifetime,
                        null,
                        pnfsId);
            } else {
                logger.trace("transferStarting: file is not found, no prior reservations for this file");

                long sizeInBytes = message.getPreallocated();
                long lifetime    = 1000*60*60;
                String description = null;
                LinkGroup linkGroup = getLinkGroupByName(linkGroupName);
                VOInfo voInfo =
                        authorizationPolicy.checkReservePermission(subject, linkGroup);

                long reservationId = reserveSpaceInLinkGroup(linkGroup.getId(),
                                                             voInfo.getVoGroup(),
                                                             voInfo.getVoRole(),
                                                             sizeInBytes,
                                                             fileAttributes.getAccessLatency(),
                                                             fileAttributes.getRetentionPolicy(),
                                                             lifetime,
                                                             description);
                useSpace(reservationId,
                        voInfo.getVoGroup(),
                        voInfo.getVoRole(),
                        sizeInBytes,
                        lifetime,
                        null,
                        pnfsId);

                /* One could inject SpaceToken and SpaceTokenDescription into storage
                 * info at this point, but since the space reservation is implicit and
                 * short lived, this information will not be of much use.
                 */
            }
        }

        private void transferStarted(PnfsId pnfsId,boolean success) throws SQLException {
                logger.trace("transferStarted({},{})", pnfsId, success);
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        File f = selectFileForUpdate(connection,pnfsId);
                        if (f.getState() == FileState.RESERVED) {
                            if(!success) {
                                    logger.error("transfer start up failed");
                                    if (f.getPnfsPath() != null) {
                                        removePnfsIdOfFileInSpace(connection, f.getId());
                                    } else {
                                        /* This reservation was created by space manager
                                         * when the transfer started. Delete it.
                                         */
                                        removeFileFromSpace(connection, f.getId());

                                        /* TODO: If we also created the reservation, we should
                                         * release it at this point, but at the moment we cannot
                                         * know who created it. It will eventually expire
                                         * automatically.
                                         */
                                    }
                            } else {
                                    updateSpaceFile(connection,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    FileState.TRANSFERRING.getStateId(),
                                                    f);
                            }
                        }
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                }
                catch(SQLException sqle) {
                        logger.error("transferStarted failed: {}", sqle.getMessage());
                        if (connection!=null) {
                            try {
                                connection.rollback();
                            }
                            catch (SQLException e) {}
                            connection_pool.returnFailedConnection(connection);
                            connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private void transferFinished(DoorTransferFinishedMessage finished) throws SQLException {
                boolean weDeleteStoredFileRecord = deleteStoredFileRecord;
                PnfsId pnfsId = finished.getPnfsId();
                long size = finished.getFileAttributes().getSize();
                boolean success = finished.getReturnCode() == 0;
                logger.trace("transferFinished({},{})", pnfsId, success);
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        File f;
                        try {
                                f = selectFileForUpdate(connection,pnfsId);
                        }
                        catch (SQLException e) {
                                if (Objects.equals(e.getSQLState(), "02000")) {
                                    logger.trace("failed to find file {}: {}", pnfsId,
                                            e.getMessage());
                                } else {
                                    logger.error("failed to find file {}: {}", pnfsId,
                                            e.getMessage());
                                }
                                if (connection!=null) {
                                    connection.rollback();
                                    connection_pool.returnConnection(connection);
                                    connection = null;
                                }
                                if (!Objects.equals(e.getSQLState(), "02000")) {
                                    throw e;
                                }
                                return;
                        }
                        long spaceId = f.getSpaceId();
                        if(f.getState() == FileState.RESERVED ||
                           f.getState() == FileState.TRANSFERRING) {
                                if(success) {
                                        if(returnFlushedSpaceToReservation && weDeleteStoredFileRecord) {
                                                RetentionPolicy rp = getSpace(spaceId).getRetentionPolicy();
                                                if(rp.equals(RetentionPolicy.CUSTODIAL)) {
                                                        //we do not delete it here, since the
                                                        // file will get flushed and we will need
                                                        // to account for that
                                                        weDeleteStoredFileRecord = false;
                                                }
                                        }
                                        if(weDeleteStoredFileRecord) {
                                                logger.trace("file transfered, " +
                                                        "deleting file record");
                                                removeFileFromSpace(connection,f.getId());
                                        }
                                        else {
                                                updateSpaceFile(connection,
                                                                null,
                                                                null,
                                                                null,
                                                                size,
                                                                null,
                                                                FileState.STORED.getStateId(),
                                                                f);
                                        }
                                }
                                else {
                                        if (f.getPnfsPath() != null) {
                                            removePnfsIdAndChangeStateOfFileInSpace(connection, f.getId(), FileState.RESERVED.getStateId());
                                        } else {
                                            /* This reservation was created by space manager
                                             * when the transfer started. Delete it.
                                             */
                                            removeFileFromSpace(connection, f.getId());

                                            /* TODO: If we also created the reservation, we should
                                             * release it at this point, but at the moment we cannot
                                             * know who created it. It will eventually expire
                                             * automatically.
                                             */
                                        }
                                }
                                connection.commit();
                                connection_pool.returnConnection(connection);
                                connection = null;
                        }
                        else {
                                logger.trace("transferFinished({}): file state={}",
                                        pnfsId, f.getState());
                                connection.commit();
                                connection_pool.returnConnection(connection);
                                connection = null;
                        }
                }
                catch(SQLException sqle) {
                        logger.error("transferFinished failed: {}",
                                sqle.getMessage());
                        if (connection!=null) {
                                try {
                                        connection.rollback();
                                }
                                catch(SQLException sqle1) {}
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private void  fileFlushed(PoolFileFlushedMessage fileFlushed) throws SQLException {
                if(!returnFlushedSpaceToReservation) {
                        return;
                }
                PnfsId pnfsId = fileFlushed.getPnfsId();
                //
                // if this file is not in srmspacefile table, silently quit
                //
                Set<File> files = dbManager.selectPrepared(fileIO,
                                                         FileIO.SELECT_BY_PNFSID,
                                                         pnfsId.toString());
                if (files.isEmpty()) {
                    return;
                }
                logger.trace("fileFlushed({})", pnfsId);
                FileAttributes fileAttributes = fileFlushed.getFileAttributes();
                AccessLatency ac = fileAttributes.getAccessLatency();
                if (ac.equals(AccessLatency.ONLINE)) {
                        logger.trace("File Access latency is ONLINE " +
                                "fileFlushed does nothing");
                        return;
                }
                long size = fileAttributes.getSize();
                Connection connection   = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        File f = selectFileForUpdate(connection,pnfsId);
                        if(f.getState() == FileState.STORED) {
                                if(deleteStoredFileRecord) {
                                        logger.trace("returnSpaceToReservation, " +
                                                "deleting file record");
                                        removeFileFromSpace(connection,f.getId());
                                }
                                else {
                                        updateSpaceFile(connection,
                                                        null,
                                                        null,
                                                        null,
                                                        size,
                                                        null,
                                                        FileState.FLUSHED.getStateId(),
                                                        f);
                                        connection.commit();
                                        connection_pool.returnConnection(connection);
                                        connection = null;
                                }
                        }
                        else {
                                logger.trace("returnSpaceToReservation({}): " +
                                        "file state={}", pnfsId, f.getState());
                                connection.commit();
                                connection_pool.returnConnection(connection);
                                connection = null;
                        }

                }
                catch(SQLException sqle) {
                        logger.error("failed to return space to reservation: {}",
                                sqle.getMessage());
                        if (connection!=null) {
                                try {
                                        connection.rollback();
                                }
                                catch(SQLException sqle1) {}
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private void  fileRemoved(PoolRemoveFilesMessage fileRemoved)
        {
                logger.trace("fileRemoved()");
                for(String pnfsIdString : fileRemoved.getFiles()) {
                        PnfsId pnfsId ;
                        try {
                                pnfsId = new PnfsId(pnfsIdString);
                        } catch (IllegalArgumentException e) {
                                logger.error("badly formed PNFS-ID: {}", pnfsIdString);
                                continue;
                        }
                        logger.trace("fileRemoved({})", pnfsId);
                        Connection connection = null;
                        try {
                                connection = connection_pool.getConnection();
                                connection.setAutoCommit(false);
                                File f = selectFileForUpdate(connection,pnfsId);
                                if ((f.getState() != FileState.RESERVED && f.getState() != FileState.TRANSFERRING) || f.getPnfsPath() == null) {
                                    removeFileFromSpace(connection,f.getId());
                                } else if (f.getState() == FileState.TRANSFERRING) {
                                    removePnfsIdAndChangeStateOfFileInSpace(connection, f.getId(), FileState.RESERVED.getStateId());
                                }
                                connection.commit();
                                connection_pool.returnConnection(connection);
                                connection = null;
                        }
                        catch(SQLException sqle) {
                                logger.trace("failed to remove file from space: {}",
                                        sqle.getMessage());
                                logger.trace("fileRemoved({}): file not in a " +
                                        "reservation, do nothing", pnfsId);
                                if (connection!=null) {
                                        try {
                                                connection.rollback();
                                        }
                                        catch(SQLException sqle1) {}
                                        connection_pool.returnFailedConnection(connection);
                                        connection = null;
                                }

                        }
                        finally {
                                if(connection != null) {
                                        connection_pool.returnConnection(connection);
                                }
                        }
                }
        }

        private void cancelUseSpace(CancelUse cancelUse)
                throws SQLException,SpaceException {
                logger.trace("cancelUseSpace({})", cancelUse);
                long reservationId = cancelUse.getSpaceToken();
                String pnfsPath    = cancelUse.getPnfsName();
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        File f;
                        try {
                                f=selectFileFromSpaceForUpdate(connection,pnfsPath,reservationId);
                        }
                        catch(SQLException sqle) {
                                //
                                // this is not an error: we are here in two cases
                                //   1) no transient file found - OK
                                //   2) more than one transient file found, less OK, but
                                //      remaining transient files will be garbage colllected after timeout
                                //
                                if(connection != null) {
                                        connection_pool.returnConnection(connection);
                                        connection = null;
                                }
                                return;
                        }
                        if(f.getState() == FileState.RESERVED ||
                           f.getState() == FileState.TRANSFERRING) {
                                try {
                                        if (f.getPnfsId() != null) {
                                                try {
                                                pnfs.deletePnfsEntry(f.getPnfsId(), pnfsPath);
                                                } catch (FileNotFoundCacheException ignored) {
                                                }
                                        }
                                        removeFileFromSpace(connection,f.getId());
                                        connection_pool.returnConnection(connection);
                                        connection = null;
                                } catch (CacheException e) {
                                    throw new SpaceException("Failed to delete " + pnfsPath +
                                                             " while attempting to cancel its reservation in space " +
                                                             reservationId + ": " + e.getMessage(), e);
                                } finally {
                                        if (connection!=null) {
                                                logger.warn("failed to " +
                                                        "remove file {}",
                                                        pnfsPath);
                                                connection_pool.returnFailedConnection(connection);
                                                connection = null;
                                        }
                                }
                        }
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnFailedConnection(connection);
                        }
                }
        }

        private long reserveSpace(String voGroup,
                                  String voRole,
                                  long sizeInBytes,
                                  AccessLatency latency ,
                                  RetentionPolicy policy,
                                  long lifetime,
                                  String description)
                throws SQLException,
                       SpaceException {
                logger.trace("reserveSpace(group={}, role={}, sz={}, " +
                        "latency={}, policy={}, lifetime={}, description={}",
                        voGroup, voRole, sizeInBytes, latency, policy, lifetime,
                        description);
                boolean needHsmBackup = policy.equals(RetentionPolicy.CUSTODIAL);
                logger.trace("policy is {}, needHsmBackup is {}", policy,
                        needHsmBackup);
                Long[] linkGroups = findLinkGroupIds(sizeInBytes,
                                                     voGroup,
                                                     voRole,
                                                     latency,
                                                     policy);
                if(linkGroups.length == 0) {
                        logger.warn("find LinkGroup Ids returned 0 linkGroups, no linkGroups found");
                        throw new NoFreeSpaceException(" no space available");
                }
                Long linkGroupId = linkGroups[0];
                return reserveSpaceInLinkGroup(
                        linkGroupId,
                                               voGroup,
                                               voRole,
                                               sizeInBytes,
                                               latency,
                                               policy,
                                               lifetime,
                                               description);
        }

        private long reserveSpace(Subject subject,
                                  long sizeInBytes,
                                  AccessLatency latency ,
                                  RetentionPolicy policy,
                                  long lifetime,
                                  String description,
                                  ProtocolInfo protocolInfo,
                                  FileAttributes fileAttributes,
                                  PnfsId pnfsId)
                throws SQLException,
                       SpaceException {
                logger.trace("reserveSpace( subject={}, sz={}, latency={}, " +
                        "policy={}, lifetime={}, description={}", subject.getPrincipals(),
                        sizeInBytes, latency, policy, lifetime, description);
                boolean needHsmBackup = policy.equals(RetentionPolicy.CUSTODIAL);
                logger.trace("policy is {}, needHsmBackup is {}", policy, needHsmBackup);
                Set<LinkGroup> linkGroups = findLinkGroupIds(sizeInBytes,
                                                             latency,
                                                             policy);
                if(linkGroups.isEmpty()) {
                        logger.warn("failed to find matching linkgroup");
                        throw new NoFreeSpaceException(" no space available");
                }
                //
                // filter out groups we are not authorized to use
                //
                Map<String,VOInfo> linkGroupNameVoInfoMap = new HashMap<>();
                for (LinkGroup linkGroup : linkGroups) {
                        try {
                                VOInfo voInfo =
                                        authorizationPolicy.checkReservePermission(subject,
                                                                                   linkGroup);
                                linkGroupNameVoInfoMap.put(linkGroup.getName(),voInfo);
                        }
                        catch (SpaceAuthorizationException e) {
                        }
                }
                if(linkGroupNameVoInfoMap.isEmpty()) {
                        logger.warn("failed to find linkgroup where user is " +
                                "authorized to reserve space.");
                        throw new SpaceAuthorizationException("Failed to find LinkGroup where user is authorized to reserve space.");
                }
                List<String> linkGroupNames = new ArrayList<>(linkGroupNameVoInfoMap.keySet());
                logger.trace("Found {} linkgroups protocolInfo={}, " +
                        "storageInfo={}, pnfsId={}", linkGroups.size(),
                        protocolInfo, fileAttributes, pnfsId);
                if (linkGroupNameVoInfoMap.size()>1 &&
                    protocolInfo != null &&
                    fileAttributes != null) {
                        try {
                                linkGroupNames = selectLinkGroupForWrite(protocolInfo, fileAttributes, linkGroupNames);
                                if(linkGroupNames.isEmpty()) {
                                        throw new SpaceAuthorizationException("PoolManagerSelectLinkGroupForWriteMessage: Failed to find LinkGroup where user is authorized to reserve space.");
                                }
                        }
                        catch (SpaceAuthorizationException e)  {
                                logger.warn("authorization problem: {}",
                                        e.getMessage());
                                throw e;
                        }
                        catch(Exception e) {
                                throw new SpaceException("Internal error : Failed to get list of link group ids from Pool Manager "+e.getMessage());
                        }

                }
                String linkGroupName = linkGroupNames.get(0);
                VOInfo voInfo        = linkGroupNameVoInfoMap.get(linkGroupName);
                LinkGroup linkGroup  = null;
                for (LinkGroup lg : linkGroups) {
                        if (lg.getName().equals(linkGroupName) ) {
                                linkGroup = lg;
                                break;
                        }
                }
                logger.trace("Chose linkgroup {}",linkGroup);
                return reserveSpaceInLinkGroup(linkGroup.getId(),
                                               voInfo.getVoGroup(),
                                               voInfo.getVoRole(),
                                               sizeInBytes,
                                               latency,
                                               policy,
                                               lifetime,
                                               description);
        }

        private LinkGroup selectLinkGroupForWrite(Subject subject, ProtocolInfo protocolInfo, FileAttributes fileAttributes, long size)
                throws SQLException
        {
            Set<LinkGroup> linkGroups =
                    findLinkGroupIds(size, fileAttributes.getAccessLatency(), fileAttributes.getRetentionPolicy());
            List<String> linkGroupNames = new ArrayList<>();
            for (LinkGroup linkGroup : linkGroups) {
                try {
                    authorizationPolicy.checkReservePermission(subject, linkGroup);
                    linkGroupNames.add(linkGroup.getName());
                }
                catch (SpaceAuthorizationException e) {
                }
            }
            linkGroupNames = selectLinkGroupForWrite(protocolInfo, fileAttributes, linkGroupNames);
            logger.trace("Found {} linkgroups protocolInfo={}, fileAttributes={}",
                    linkGroups.size(), protocolInfo, fileAttributes);

            if (!linkGroupNames.isEmpty()) {
                String linkGroupName = linkGroupNames.get(0);
                for (LinkGroup lg : linkGroups) {
                    if (lg.getName().equals(linkGroupName) ) {
                        return lg;
                    }
                }
            }
            return null;
        }

        private List<String> selectLinkGroupForWrite(ProtocolInfo protocolInfo, FileAttributes fileAttributes,
                                                     Collection<String> linkGroups)
        {
                String protocol = protocolInfo.getProtocol() + '/' + protocolInfo.getMajorVersion();
                String hostName =
                        (protocolInfo instanceof IpProtocolInfo)
                                ? ((IpProtocolInfo) protocolInfo).getSocketAddress().getAddress().getHostAddress()
                                : null;

                List<String> outputLinkGroups = new ArrayList<>(linkGroups.size());
                for (String linkGroup: linkGroups) {
                    PoolPreferenceLevel[] level =
                            poolMonitor.getPoolSelectionUnit().match(PoolSelectionUnit.DirectionType.WRITE,
                                    hostName,
                                    protocol,
                                    fileAttributes,
                                    linkGroup);
                    if (level.length > 0) {
                        outputLinkGroups.add(linkGroup);
                    }
                }
                return outputLinkGroups;
        }

        private long reserveSpaceInLinkGroup(long linkGroupId,
                                             String voGroup,
                                             String voRole,
                                             long sizeInBytes,
                                             AccessLatency latency,
                                             RetentionPolicy policy,
                                             long lifetime,
                                             String description)
                throws SQLException
        {
                logger.trace("reserveSpaceInLinkGroup(linkGroupId={}, " +
                        "group={}, role={}, sz={}, latency={}, policy={}, " +
                        "lifetime={}, description={})", linkGroupId, voGroup,
                        voRole, sizeInBytes, latency, policy, lifetime,
                        description);
                Connection connection =null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        long spaceReservationId = getNextToken(connection);
                        insertSpaceReservation(connection,
                                               spaceReservationId,
                                               voGroup,
                                               voRole,
                                               policy,
                                               latency,
                                               linkGroupId,
                                               sizeInBytes,
                                               lifetime,
                                               description,
                                               0,
                                               0,
                                               0);
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                        return spaceReservationId;
                }
                catch(SQLException sqle) {
                        logger.error("failed to reserve space: {}",
                                sqle.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        private long useSpace(long reservationId,
                              Subject subject,
                              long sizeInBytes,
                              long lifetime,
                              String pnfsPath,
                              PnfsId pnfsId)
                throws SQLException, SpaceException
        {
            String effectiveGroup;
            String effectiveRole;
            String primaryFqan = Subjects.getPrimaryFqan(subject);
            if (primaryFqan != null) {
                FQAN fqan = new FQAN(primaryFqan);
                effectiveGroup = fqan.getGroup();
                effectiveRole = fqan.getRole();
            } else {
                effectiveGroup = Subjects.getUserName(subject);
                effectiveRole = null;
            }
            return useSpace(reservationId,
                    effectiveGroup,
                    effectiveRole,
                    sizeInBytes,
                    lifetime,
                    pnfsPath,
                    pnfsId);
        }

        private long useSpace(long reservationId,
                              String voGroup,
                              String voRole,
                              long sizeInBytes,
                              long lifetime,
                              String pnfsPath,
                              PnfsId pnfsId)
                throws SQLException,SpaceException {
                Connection connection =null;
                //
                // check that there is no such file already being transferred
                //
                FsPath path;
                if (pnfsPath != null) {
                    path = new FsPath(pnfsPath);
                    Set<File> files=dbManager.selectPrepared(fileIO,
                            FileIO.SELECT_TRANSFERRING_OR_RESERVED_BY_PNFSPATH,
                            path.toString());
                    if (files!=null && !files.isEmpty()) {
                        throw new SQLException("Already have "+files.size()+" record(s) with pnfsPath="+pnfsPath);
                    }
                } else {
                    path = null;
                }
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        long fileId = getNextToken(connection);
                        insertFileInSpace(connection,
                                          fileId,
                                          voGroup,
                                          voRole,
                                          reservationId,
                                          sizeInBytes,
                                          lifetime,
                                          path,
                                          pnfsId,
                                          SpaceState.RESERVED.getStateId());
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;
                        return fileId;
                }
                catch(SQLException | SpaceException sqle) {
                        logger.error("failed to insert file into space: {}",
                                sqle.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                } finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }

        /**
         * Called upon intercepting PoolMgrSelectWritePoolMsg requests.
         *
         * Injects the link group name into the request message. Also adds SpaceToken, LinkGroup,
         * and SpaceFileId flags to StorageInfo. These are accessed when space manager intercepts
         * the subsequent PoolAcceptFileMessage.
         */
        private void selectPool(PoolMgrSelectWritePoolMsg selectWritePool) throws SQLException, SpaceException
        {
            logger.trace("selectPoolOnRequest({})", selectWritePool);
            FileAttributes fileAttributes = selectWritePool.getFileAttributes();
            String defaultSpaceToken = fileAttributes.getStorageInfo().getMap().get("writeToken");
            Subject subject = selectWritePool.getSubject();
            boolean hasIdentity =
                    !Subjects.getFqans(subject).isEmpty() || Subjects.getUserName(subject) != null;

            String pnfsPath = new FsPath(checkNotNull(selectWritePool.getPnfsPath())).toString();
            Set<File> files = dbManager.selectPrepared(
                    fileIO,
                    "SELECT * FROM " + FileIO.SRM_SPACEFILE_TABLE + " WHERE pnfspath=? and pnfsid is null and deleted != 1",
                    pnfsPath);
            File file = Iterables.getFirst(files, null);

            if (file != null) {
                /*
                 * This takes care of records created by SRM before
                 * transfer has started
                 */
                Space space = getSpace(file.getSpaceId());
                LinkGroup linkGroup = getLinkGroup(space.getLinkGroupId());
                String linkGroupName = linkGroup.getName();
                selectWritePool.setLinkGroup(linkGroupName);

                StorageInfo storageInfo = fileAttributes.getStorageInfo();
                storageInfo.setKey("SpaceToken", Long.toString(space.getId()));
                storageInfo.setKey("LinkGroup", linkGroupName);
                fileAttributes.setAccessLatency(space.getAccessLatency());
                fileAttributes.setRetentionPolicy(space.getRetentionPolicy());

                if (fileAttributes.getSize() == 0 && file.getSizeInBytes() > 1) {
                    fileAttributes.setSize(file.getSizeInBytes());
                }
                if (space.getDescription() != null) {
                    storageInfo.setKey("SpaceTokenDescription", space.getDescription());
                }
                storageInfo.setKey("SpaceFileId", Long.toString(file.getId()));
                logger.trace("selectPoolOnRequest: found linkGroup = {}, " +
                        "forwarding message", linkGroupName);
            } else if (defaultSpaceToken != null) {
                logger.trace("selectPoolOnRequest: file is not " +
                        "found, found default space " +
                        "token, calling useSpace()");
                Space space = getSpace(Long.parseLong(defaultSpaceToken));
                LinkGroup linkGroup = getLinkGroup(space.getLinkGroupId());
                String linkGroupName = linkGroup.getName();
                selectWritePool.setLinkGroup(linkGroupName);

                StorageInfo storageInfo = selectWritePool.getStorageInfo();
                storageInfo.setKey("SpaceToken", Long.toString(space.getId()));
                storageInfo.setKey("LinkGroup", linkGroupName);
                fileAttributes.setAccessLatency(space.getAccessLatency());
                fileAttributes.setRetentionPolicy(space.getRetentionPolicy());

                if (space.getDescription() != null) {
                    storageInfo.setKey("SpaceTokenDescription", space.getDescription());
                }
                logger.trace("selectPoolOnRequest: found linkGroup = {}, " +
                        "forwarding message", linkGroupName);
            } else if (reserveSpaceForNonSRMTransfers && hasIdentity) {
                logger.trace("selectPoolOnRequest: file is " +
                        "not found, no prior " +
                        "reservations for this file");

                LinkGroup linkGroup =
                        selectLinkGroupForWrite(subject, selectWritePool
                                .getProtocolInfo(), fileAttributes, selectWritePool.getPreallocated());
                if (linkGroup != null) {
                    String linkGroupName = linkGroup.getName();
                    selectWritePool.setLinkGroup(linkGroupName);
                    fileAttributes.getStorageInfo().setKey("LinkGroup", linkGroupName);
                    logger.trace("selectPoolOnRequest: found linkGroup = {}, " +
                            "forwarding message", linkGroupName);
                } else {
                    logger.trace("selectPoolOnRequest: did not find linkGroup that can " +
                            "hold this file, processing file without space reservation.");
                }
            } else {
                logger.trace("selectPoolOnRequest: file is " +
                        "not found, no prior " +
                        "reservations for this file " +
                        "reserveSpaceForNonSRMTransfers={} " +
                        "subject={}",
                        reserveSpaceForNonSRMTransfers,
                        subject.getPrincipals());
            }
        }

        private void namespaceEntryDeleted(PnfsDeleteEntryNotificationMessage msg) throws SQLException {
                File file;
                try {
                        Set<File> files = dbManager.selectPrepared(fileIO,
                                                                 FileIO.SELECT_BY_PNFSID,
                                                                 msg.getPnfsId().toString());
                        if (files.isEmpty()) {
                            return;
                        }
                        if (files.size()>1) {
                                throw new SQLException("found two records with pnfsId=" + msg.getPnfsId());
                        }
                        file = Iterables.getOnlyElement(files);
                }
                catch (Exception e) {
                        logger.error("failed to retrieve file {} {}: {}",
                                msg.getPnfsId() != null ? msg.getPnfsId() : "(no PNFS-ID)",
                                msg.getPnfsPath() != null ? msg.getPnfsPath() : "(no path)",
                                e.getMessage());
                        return;
                }
                logger.trace("Marking file as deleted {}", file);
                Connection connection = null;
                int rc;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        File f = selectFileForUpdate(connection,file.getId());
                        if ((f.getState() != FileState.RESERVED && f.getState() != FileState.TRANSFERRING) || f.getPnfsPath() == null) {
                            rc = dbManager.update(connection,
                                    FileIO.UPDATE_DELETED_FLAG,
                                    1,
                                    f.getId());
                            if (rc!=1) {
                                throw new SQLException("Update failed, row count="+rc);
                            }
                        } else if (f.getState() == FileState.TRANSFERRING) {
                            removePnfsIdAndChangeStateOfFileInSpace(connection, f.getId(), FileState.RESERVED.getStateId());
                        }
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection=null;
                }
                catch (SQLException e) {
                        logger.error("failed to mark file {} as deleted: {}",
                                file, e.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                        }
                        throw e;
                }
        }


        private void getSpaceMetaData(GetSpaceMetaData gsmd) throws SQLException{
                long[] tokens = gsmd.getSpaceTokens();
                if(tokens == null) {
                        throw new IllegalArgumentException("null space tokens");
                }
                Space[] spaces = new Space[tokens.length];
                for(int i=0;i<spaces.length; ++i){

                        Space space = null;
                        try {
                                space = getSpace(tokens[i]);
                                // Expiration of space reservations is a background activity and is not immediate.
                                // S2 tests however expect the state to be accurate at any point, hence we report
                                // the state as EXPIRED even when the actual state has not been updated in the
                                // database yet. See usecase.CheckGarbageSpaceCollector (S2).
                                if (space.getState().equals(SpaceState.RESERVED)) {
                                        long expirationTime = space.getExpirationTime();
                                        if (expirationTime > -1 && expirationTime - System.currentTimeMillis() <= 0) {
                                                space.setState(SpaceState.EXPIRED);
                                        }
                                }
                        }
                        catch(Exception e) {
                                logger.error("failed to find space {}: {}",
                                        tokens[i], e.getMessage());
                        }
                        spaces[i] = space;
                }
                gsmd.setSpaces(spaces);
        }

        private void getSpaceTokens(GetSpaceTokens gst) throws SQLException{
                String description = gst.getDescription();

                long [] tokens = getSpaceTokens(gst.getSubject(), description);
                gst.setSpaceToken(tokens);
        }

        private void getFileSpaceTokens(GetFileSpaceTokensMessage getFileTokens) throws SQLException{
                PnfsId pnfsId = getFileTokens.getPnfsId();
                String pnfsPath = getFileTokens.getPnfsPath();
                getFileTokens.setSpaceToken(getFileSpaceTokens(pnfsId,pnfsPath));
        }

        private void extendLifetime(ExtendLifetime extendLifetime) throws SQLException, SpaceException {
                long token            = extendLifetime.getSpaceToken();
                long newLifetime      = extendLifetime.getNewLifetime();
                Connection connection = null;
                try {
                        connection = connection_pool.getConnection();
                        connection.setAutoCommit(false);
                        Space space = selectSpaceForUpdate(connection,token);
                        if(SpaceState.isFinalState(space.getState())) {
                                connection.rollback();
                                connection_pool.returnConnection(connection);
                                connection = null;
                                throw new SpaceException("Space Is already Released");
                        }
                        long creationTime = space.getCreationTime();
                        long lifetime = space.getLifetime();
                        if(lifetime == -1) {
                                connection.rollback();
                                connection_pool.returnConnection(connection);
                                connection = null;
                                return;
                        }
                        if(newLifetime == -1) {
                                dbManager.update(connection,
                                               SpaceReservationIO.UPDATE_LIFETIME,
                                               newLifetime,
                                               token);
                                connection.commit();
                                connection_pool.returnConnection(connection);
                                connection = null;
                                return;
                        }
                        long currentTime = System.currentTimeMillis();
                        long remainingLifetime = creationTime+lifetime-currentTime;
                        if(remainingLifetime > newLifetime) {
                                connection.rollback();
                                connection_pool.returnConnection(connection);
                                connection = null;
                                return;
                        }
                        dbManager.update(connection,
                                       SpaceReservationIO.UPDATE_LIFETIME,
                                       newLifetime,
                                       token);
                        connection.commit();
                        connection_pool.returnConnection(connection);
                        connection = null;

                }
                catch(SQLException sqle) {
                        logger.error("failed to extend lifetime for {}: {}",
                                token, sqle.getMessage());
                        if (connection!=null) {
                                connection.rollback();
                                connection_pool.returnFailedConnection(connection);
                                connection = null;
                        }
                        throw sqle;
                }
                finally {
                        if(connection != null) {
                                connection_pool.returnConnection(connection);
                        }
                }
        }
}
