package tmc.shared.id;

import java.util.UUID;

public final class UuidGenerator implements IdGenerator {
  @Override
  public String next() {
    return UUID.randomUUID().toString();
  }
}
