package cn.wanfu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;

class QuestionScanDispatchTest {
    Db db;QuestionScanService scans;
    @BeforeEach void setup(){
        db=mock(Db.class);var sec=mock(Security.class);when(sec.now()).thenReturn(LocalDateTime.of(2026,10,31,10,0));var ocr=mock(QuestionOcrClient.class);when(ocr.configured()).thenReturn(true);
        scans=new QuestionScanService(db,sec,mock(FileService.class),mock(PrivacyService.class),mock(LearningService.class),ocr,new ObjectMapper(),mock(PlatformTransactionManager.class));
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void cleanup(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();scans.close();}
    @Test void committedScanWakesTheWorkerAfterTheTransactionCompletes(){
        scans.wakeAfterCommit();verifyNoInteractions(db);var callbacks=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        verify(db,timeout(1000)).optional("SELECT id FROM question_scan WHERE status='QUEUED' ORDER BY id LIMIT 1");
    }
    @Test void rolledBackCreationDoesNotWakeTheWorker(){
        scans.wakeAfterCommit();var callbacks=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(callback->callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));verifyNoInteractions(db);
    }
    @Test void shutdownDoesNotSubmitAnAfterCommitTask(){
        scans.wakeAfterCommit();var callbacks=TransactionSynchronizationManager.getSynchronizations();TransactionSynchronizationManager.clearSynchronization();scans.close();
        callbacks.forEach(TransactionSynchronization::afterCommit);verifyNoInteractions(db);
    }
}
