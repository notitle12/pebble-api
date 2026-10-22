package com.pebble.api.update.application;
import com.pebble.api.update.domain.*;
import com.pebble.api.update.infrastructure.ServiceUpdateRepository;
import com.pebble.api.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceUpdateService {
    private final ServiceUpdateRepository updates;
    public Page<ServiceUpdate> publicList(UpdateType type, int page, int size) {
        return updates.findPublic(type, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt", "id")));
    }
    public ServiceUpdate publicDetail(long id) {
        return updates.findByIdAndDeletedAtIsNullAndStatus(id, UpdateStatus.PUBLISHED).orElseThrow(ServiceUpdateService::notFound);
    }
    public Page<ServiceUpdate> managementList(UpdateType type, UpdateStatus status, int page, int size) {
        return updates.findManagement(type, status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }
    public ServiceUpdate managementDetail(long id) {
        return updates.findByIdAndDeletedAtIsNull(id).orElseThrow(ServiceUpdateService::notFound);
    }
    @Transactional
    public ServiceUpdate create(UpdateInput input) {
        return updates.saveAndFlush(new ServiceUpdate(input.title(), input.summary(), input.body(), input.type(), input.version(), input.status()));
    }
    @Transactional
    public ServiceUpdate replace(long id, long expectedRevision, UpdateInput input) {
        ServiceUpdate update = current(id, expectedRevision);
        update.change(input.title(), input.summary(), input.body(), input.type(), input.version(), input.status());
        return updates.saveAndFlush(update);
    }
    @Transactional
    public void delete(long id, long expectedRevision) {
        current(id, expectedRevision).delete();
        updates.flush();
    }
    private ServiceUpdate current(long id, long expectedRevision) {
        ServiceUpdate value = updates.lockById(id).orElseThrow(ServiceUpdateService::notFound);
        if (value.getRevision() != expectedRevision) throw new ApplicationException(UpdateError.UPDATE_CONFLICT);
        return value;
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
