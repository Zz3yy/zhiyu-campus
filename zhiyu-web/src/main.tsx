import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { GardenApp } from './garden/GardenApp'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <GardenApp />
  </StrictMode>,
)
