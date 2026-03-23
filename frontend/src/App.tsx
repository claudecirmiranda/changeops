// src/App.tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ChangesPage } from './app/routes/ChangesPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/changes" replace />} />
        <Route path="/changes" element={<ChangesPage />} />
        <Route path="*" element={<Navigate to="/changes" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
