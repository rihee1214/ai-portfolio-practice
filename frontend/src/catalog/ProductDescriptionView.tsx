import { Fragment } from 'react'
import { descriptionImageUrl, type DescriptionBlock } from './ProductDescription'
import './product-description.css'

export function ProductDescriptionView({ plain, rich }: { plain: string; rich?: DescriptionBlock[] | null }) {
  return <div className="product-description-body">
    {rich?.length ? rich.map((block, index) => {
      if (block.kind === 'image') return <img key={block.assetId} src={descriptionImageUrl(block.assetId)} alt={block.alt} loading="lazy" />
      const Tag = block.kind === 'heading' ? 'h3' : 'p'
      return <Tag key={index}>{block.runs.length ? block.runs.map((run, position) => {
        let text = <Fragment key={position}>{run.text}</Fragment>
        if (run.italic) text = <em key={position}>{text}</em>
        if (run.bold) text = <strong key={position}>{text}</strong>
        return text
      }) : <br />}</Tag>
    }) : <p>{plain || '등록된 상품 설명이 없습니다.'}</p>}
  </div>
}
