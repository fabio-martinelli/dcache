package org.dcache.webadmin.controller.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dcache.admin.webadmin.datacollector.datatypes.MoverInfo;
import org.dcache.webadmin.controller.ActiveTransfersService;
import org.dcache.webadmin.controller.exceptions.ActiveTransfersServiceException;
import org.dcache.webadmin.controller.util.BeanDataMapper;
import org.dcache.webadmin.model.dataaccess.DAOFactory;
import org.dcache.webadmin.model.dataaccess.MoverDAO;
import org.dcache.webadmin.model.exceptions.DAOException;
import org.dcache.webadmin.view.beans.ActiveTransfersBean;
import org.dcache.webadmin.view.util.SelectableWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jans
 */
public class StandardActiveTransfersService implements ActiveTransfersService {

    private static final Logger _log = LoggerFactory.getLogger(
            StandardActiveTransfersService.class);
    private DAOFactory _daoFactory;

    public StandardActiveTransfersService(DAOFactory daoFactory) {
        _daoFactory = daoFactory;
    }

    @Override
    public List<SelectableWrapper<ActiveTransfersBean>> getActiveTransferBeans()
            throws ActiveTransfersServiceException {
        try {
            List<MoverInfo> moverInfos = getMoverDAO().getActiveTransfers();
            _log.debug("aquired moverInfos: " + moverInfos.size());
            List<SelectableWrapper<ActiveTransfersBean>> activeTransfers =
                    new ArrayList<SelectableWrapper<ActiveTransfersBean>>();
            for (MoverInfo currentMover : moverInfos) {
                ActiveTransfersBean newTransfer =
                        BeanDataMapper.moverModelToView(currentMover);
                activeTransfers.add(new SelectableWrapper<ActiveTransfersBean>(newTransfer));
            }
            _log.debug("returned activeTransferBeans: " + activeTransfers.size());
            return activeTransfers;
        } catch (DAOException ex) {
            throw new ActiveTransfersServiceException(ex);
        }

    }

    private MoverDAO getMoverDAO() {
        return _daoFactory.getMoverDAO();
    }

    @Override
    public void killTransfers(List<SelectableWrapper<ActiveTransfersBean>> transfers)
            throws ActiveTransfersServiceException {
        Map<String, Set<Integer>> pools = getTargetedPoolsJobIds(transfers);
        StringBuilder failedIds = new StringBuilder();
        boolean failedIdsExist = false;
        for (String pool : pools.keySet()) {
            try {
                killMoversOfPool(pool, pools.get(pool));
            } catch (DAOException ex) {
                failedIdsExist = true;
                failedIds.append(ex.getMessage());
            }
        }
        if (failedIdsExist) {
            throw new ActiveTransfersServiceException(failedIds.toString());
        }
    }

    private void killMoversOfPool(String pool, Set<Integer> jobIds) throws DAOException {
        getMoverDAO().killMoversOnSinglePool(jobIds, pool);
    }

    private Map<String, Set<Integer>> getTargetedPoolsJobIds(List<SelectableWrapper<ActiveTransfersBean>> transfers) {
        Map<String, Set<Integer>> pools = new HashMap<String, Set<Integer>>();
        for (SelectableWrapper<ActiveTransfersBean> transfer : transfers) {
            if (transfer.isSelected() && !transfer.getWrapped().getPool().isEmpty()) {
                if (pools.containsKey(transfer.getWrapped().getPool())) {
                    Set<Integer> jobIds = pools.get(transfer.getWrapped().getPool());
                    jobIds.add((int) transfer.getWrapped().getJobId());
                } else {
                    Set<Integer> jobIds = new HashSet<Integer>();
                    jobIds.add((int) transfer.getWrapped().getJobId());
                    pools.put(transfer.getWrapped().getPool(), jobIds);
                }
            }
        }
        return pools;
    }

    public void setDAOFactory(DAOFactory daoFactory) {
        _daoFactory = daoFactory;
    }
}