package tmc.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlationId";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

  public static String currentId() {
    return MDC.get(MDC_KEY);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain
  ) throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String id = supplied != null && SAFE_ID.matcher(supplied).matches()
        ? supplied
        : UUID.randomUUID().toString();
    String previous = currentId();
    MDC.put(MDC_KEY, id);
    try {
      response.setHeader(HEADER, id);
      chain.doFilter(request, response);
    } finally {
      if (previous == null) MDC.remove(MDC_KEY);
      else MDC.put(MDC_KEY, previous);
    }
  }
}
