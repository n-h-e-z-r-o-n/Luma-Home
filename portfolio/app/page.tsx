import NavBar from '@/components/NavBar'
import Form from 'next/form';

export default function Page() {
  return (
    <div>
       <NavBar />
        <SEO
        title="WRLDS - Smart Textile Technology"
        description="WRLDS Technologies: Pioneering smart engineering solutions with textile sensors for sports, safety, and performance monitoring."
        imageUrl="/lovable-uploads/526dc38a-25fa-40d4-b520-425b23ae0464.png"
        keywords={['smart textiles', 'wearable technology', 'textile sensors', 'sports tech', 'safety monitoring', 'performance analytics', 'manufacturing']}
      />


    </div>


  );
}

