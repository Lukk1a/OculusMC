import ClientPage from "./ClientPage";

export function generateStaticParams() { 
  return [{ name: "dummy" }]; 
}

export default function Page() { 
  return <ClientPage />; 
}

