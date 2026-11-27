package tmc.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"tmc.accounts.enabled", "tmc.checkin.personal-self.enabled"}, havingValue = "true")
class PersonalCheckInController {
  private final StationCheckInController checkIn;

  PersonalCheckInController(StationCheckInController checkIn) {
    this.checkIn = checkIn;
  }

  @PostMapping("/workplace/v1/me/check-in/personal")
  ResponseEntity<?> record(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
      @CookieValue(value = WorkplaceController.DEVICE_COOKIE, required = false) String device,
      @RequestBody StationCheckInController.Scan body) {
    return checkIn.personalCheckIn(auth, device, body);
  }
}
