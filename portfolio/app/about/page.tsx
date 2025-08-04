// app/about/page.tsx
import Image from "next/image";
import Link from "next/link";

export default function AboutPage() {
  return (
    <main className="about-page px-8 py-12 max-w-5xl mx-auto">
      <section className="flex flex-col md:flex-row gap-10 items-start">
        <div className="relative w-64 h-64 rounded-full overflow-hidden border-4 border-gray-800 shadow-xl">
          <Image
            src="/images/me.png"
            alt="Hezron Wekesa"
            fill
            className="object-cover"
          />
        </div>

        <div className="flex-1">
          <h1 className="text-4xl font-bold mb-2">Hezron Wekesa Nanagulu</h1>
          <h2 className="text-xl text-gray-400 mb-4">Software Engineer | Full Stack Developer</h2>

          <p className="text-lg leading-relaxed text-gray-200 mb-4">
            I'm a passionate and detail-oriented software developer based in Nairobi, Kenya.
            I specialize in building robust web and mobile applications, crafting elegant UIs,
            and writing clean, maintainable code. With a background in both front-end and back-end technologies,
            I enjoy tackling complex challenges and delivering scalable solutions.
          </p>

          <p className="text-lg leading-relaxed text-gray-300 mb-4">
            My experience spans across cloud computing, cybersecurity, and AI-driven applications.
            I’ve worked with teams across Kenya and internationally to build impactful systems — from
            smart agriculture tools to healthcare automation.
          </p>

          <div className="flex gap-4 mt-6">
            <Link href="https://www.linkedin.com/in/hezron-wekesa-b58583216/" target="_blank">
              <span className="underline text-blue-400 hover:text-blue-300">LinkedIn</span>
            </Link>
            <a href="mailto:hezron.w12@gmail.com" className="underline text-blue-400 hover:text-blue-300">
              Email
            </a>
          </div>
        </div>
      </section>

      <section className="mt-16">
        <h3 className="text-2xl font-semibold mb-4">Technical Skills</h3>
        <ul className="grid grid-cols-2 md:grid-cols-3 gap-2 text-gray-300 list-disc list-inside">
          <li>Python, Java, JavaScript (React)</li>
          <li>Flask, Django, Spring Boot, Node.js</li>
          <li>React, Angular, Bootstrap</li>
          <li>MySQL, PostgreSQL, MongoDB</li>
          <li>React Native, Flutter, Kotlin</li>
          <li>Cybersecurity, Cloud Computing</li>
        </ul>
      </section>

      <section className="mt-16">
        <h3 className="text-2xl font-semibold mb-4">Certifications & Training</h3>
        <ul className="list-disc list-inside text-gray-300 space-y-1">
          <li>Full Stack Software Developer – IBM</li>
          <li>UX Design – Coursera</li>
          <li>Scrum Master – LearnQuest</li>
          <li>IT Automation with Python – Google</li>
          <li>Cybersecurity Specialization – Coursera</li>
          <li>AI Developer – IBM</li>
        </ul>
      </section>

      <section className="mt-16">
        <h3 className="text-2xl font-semibold mb-4">Interests</h3>
        <p className="text-gray-300">
          I’m passionate about tech innovation across Africa, especially in AI, cloud computing,
          and cybersecurity. I enjoy collaborating at hackathons, engaging in developer communities,
          and exploring the intersection of technology and impact.
        </p>
      </section>
    </main>
  );
}
