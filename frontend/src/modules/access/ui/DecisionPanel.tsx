import type { VerifyResult } from "../model/types";

export function DecisionPanel({ result }: { result: VerifyResult | null }) {
  return (
    <section className="facility-card facility-result">
      <h2>Access result detail</h2>
      {!result ? (
        <p className="facility-muted">Kết quả sẽ hiện sau khi Edge xử lý lượt quét.</p>
      ) : (
        <>
          <dl className="facility-result-grid">
            {[
              ["Quyết định", result.decision],
              ["Lệnh cổng", result.command],
              ["Qua cổng", result.passage],
              ["Chứng cứ", result.evidence ?? "—"],
            ].map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <p className="facility-event">
            Event ID <span>{result.eventId}</span>
          </p>
          {result.steps?.length > 0 && (
            <details>
              <summary>Các bước xác minh</summary>
              <ol>
                {result.steps.map((step, index) => (
                  <li key={index}>{step}</li>
                ))}
              </ol>
            </details>
          )}
        </>
      )}
    </section>
  );
}
