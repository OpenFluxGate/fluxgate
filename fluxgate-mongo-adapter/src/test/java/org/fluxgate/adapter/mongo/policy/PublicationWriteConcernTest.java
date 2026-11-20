package org.fluxgate.adapter.mongo.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicationWriteConcernTest {
  @Test
  @SuppressWarnings("unchecked")
  void forcesMajorityWithoutDiscardingConfiguredQuorumTimeoutOrJournal() {
    MongoDatabase database = mock(MongoDatabase.class);
    MongoCollection<Document> collection = mock(MongoCollection.class, RETURNS_SELF);
    when(database.getWriteConcern())
        .thenReturn(WriteConcern.W1.withWTimeout(2, TimeUnit.SECONDS).withJournal(true));
    when(database.getCollection(anyString())).thenReturn(collection);

    new MongoPolicyRepository(database, "rules");

    ArgumentCaptor<WriteConcern> concerns = ArgumentCaptor.forClass(WriteConcern.class);
    verify(collection, times(3)).withWriteConcern(concerns.capture());
    assertThat(concerns.getAllValues())
        .allSatisfy(
            concern -> {
              assertThat(concern.getWObject()).isEqualTo("majority");
              assertThat(concern.getWTimeout(TimeUnit.MILLISECONDS)).isEqualTo(2000L);
              assertThat(concern.getJournal()).isTrue();
            });
  }
}
