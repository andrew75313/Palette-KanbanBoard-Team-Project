package com.eight.palette.domain.column.repository;

import com.eight.palette.domain.column.entity.ColumnInfo;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnsRepository extends JpaRepository<ColumnInfo, Long> {

    @Query("SELECT c FROM ColumnInfo c WHERE c.board.id = :boardId AND c.status = 'ACTIVE' ORDER BY c.position ASC")
    List<ColumnInfo> findActiveColumnsByBoardIdOrderByPosition(Long boardId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "javax.persistence.lock.timeout", value = "60000")
    })
    @Query("SELECT c FROM ColumnInfo c WHERE c.id = :columnId")
    Optional<ColumnInfo> findByIdWithLock(Long columnId);
}
