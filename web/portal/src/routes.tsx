import { Navigate, type RouteObject } from 'react-router'
import { Shell } from './components/Shell'
import { DeviceListPage } from './pages/DeviceListPage'
import { NotFound } from './pages/NotFound'

export const routes: RouteObject[] = [
  {
    element: <Shell />,
    children: [
      { index: true, element: <Navigate to="/devices" replace /> },
      { path: 'devices', element: <DeviceListPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]
