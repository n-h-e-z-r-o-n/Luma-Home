import NavBar from '@/components/NavBar'
import HomeSection1 from '@/components/HomeSection1'

import "./globals.css"

export default function Page() {
  return (
      <>
       <NavBar />
       <HomeSection1 />
       <div className={"main"}></div>
      </>

  );
}

