import { useEffect, useState } from "react";
import { publicApi, type GiftInfo } from "../api/client";
import { QrCode } from "../ui/QrCode";
import { fmtTime } from "../lib/format";

interface Props {
  eventId: string;
  eventName: string;
  gift: GiftInfo;
  /** токен верификации — для опроса статуса подарка */
  token: string;
}

/**
 * Экран подарка: QR-код (ссылка с подписанным токеном для панели стенда) и короткий код.
 * Промоутер сканирует QR штатной камерой телефона; статус «выдан» подтягивается опросом каждые 10 с.
 */
export function GiftScreen({ eventId, eventName, gift: initial, token }: Props) {
  const [gift, setGift] = useState<GiftInfo>(initial);

  useEffect(() => {
    if (gift.awarded) return;
    const id = window.setInterval(async () => {
      try {
        const g = await publicApi.gift(eventId, token);
        setGift(g);
        if (g.awarded) window.clearInterval(id);
      } catch {
        /* сеть моргнула — попробуем в следующий раз */
      }
    }, 10000);
    return () => window.clearInterval(id);
  }, [eventId, token, gift.awarded]);

  if (!gift.enabled) return null;

  return (
    <section className="card center">
      <h2>Ваш подарок</h2>
      {gift.awarded ? (
        <>
          <div className="badge ok">Подарок выдан{gift.awardedAt ? ` в ${fmtTime(gift.awardedAt)}` : ""}</div>
          <p className="mt">Спасибо, что были с нами{eventName ? ` на «${eventName}»` : ""}!</p>
        </>
      ) : (
        <>
          <p className="muted small">Покажите этот экран промоутеру на стенде — он отсканирует QR-код камерой телефона.</p>
          {gift.giftUrl && <QrCode value={gift.giftUrl} label="QR-код подарка" />}
          {gift.giftCode && (
            <>
              <p className="muted small" style={{ marginBottom: 0 }}>Если QR не читается — назовите код:</p>
              <div className="gift-code">{gift.giftCode}</div>
            </>
          )}
          <p className="muted small mt">Экран можно закрыть и открыть позже по той же ссылке — QR сохранится.</p>
        </>
      )}
    </section>
  );
}
