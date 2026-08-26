import axios from 'axios'

/** 공용 API 클라이언트. 개발 중에는 vite proxy(/api → localhost:8080)를 탄다. */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10_000,
})
