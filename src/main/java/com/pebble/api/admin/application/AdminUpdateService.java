package com.pebble.api.admin.application;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.global.exception.*;
import com.pebble.api.update.application.*;
import com.pebble.api.update.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminUpdateService {
    private final AdminAccountRepository accounts;
    private final ServiceUpdateService updates;
    public Page<ServiceUpdate> list(long actor, UpdateType type, UpdateStatus status, int page, int size) {
        requireOperator(actor); return updates.managementList(type, status, page, size);
    }
    public ServiceUpdate detail(long actor, long id) { requireOperator(actor); return updates.managementDetail(id); }
    public ServiceUpdate create(long actor, UpdateInput input) { requireOperator(actor); return updates.create(input); }
    public ServiceUpdate replace(long actor, long id, long revision, UpdateInput input) {
        requireOperator(actor); return updates.replace(id, revision, input);
    }
    public void delete(long actor, long id, long revision) { requireOperator(actor); updates.delete(id, revision); }
    private void requireOperator(long id) {
        var actor = accounts.findForAuthenticationById(id).orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
