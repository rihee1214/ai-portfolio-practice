/** Read-only causal proof for the shipment produced by this exact public purchase. */
export type PublicPurchaseProof = {
  order: { id: string; order_number: string; state: string; amount: number }
  shipments: { id: string; order_id: string; order_number: string; state: string }[]
  shipmentConsumers: { shipment_id: string; order_id: string; order_number: string;
    job_id: string | null; job_type: string | null; cause_key: string | null; dedupe_key: string | null;
    status: string | null; job_attempts: number | null; claim_job_id: string | null; claim_attempt: number | null }[]
}

export function shipmentConsumerComplete(number: string, proof: PublicPurchaseProof): boolean {
  if (proof.order.order_number !== number || proof.order.state !== 'PAID'
    || proof.shipments.length !== 1 || proof.shipmentConsumers.length !== 1) return false
  const shipment = proof.shipments[0], consumer = proof.shipmentConsumers[0]
  return shipment.order_id === proof.order.id && shipment.order_number === number
    && consumer.shipment_id === shipment.id && consumer.order_id === proof.order.id && consumer.order_number === number
    && typeof consumer.job_id === 'string' && consumer.job_id.length > 0 && consumer.job_type === 'SHIPMENT_CREATED'
    && consumer.cause_key === 'created:' + shipment.id && consumer.dedupe_key === 'created:' + shipment.id
    && consumer.status === 'SUCCEEDED' && Number.isInteger(consumer.job_attempts) && (consumer.job_attempts ?? 0) > 0
    && consumer.claim_job_id === consumer.job_id && consumer.claim_attempt === consumer.job_attempts
}
