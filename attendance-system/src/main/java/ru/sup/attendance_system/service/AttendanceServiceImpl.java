package ru.sup.attendance_system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sup.attendance_system.dto.AttendanceDTO;
import ru.sup.attendance_system.entity.*;
import ru.sup.attendance_system.repository.*;
import ru.sup.attendance_system.service.interfaces.AttendanceService;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final LessonRepository lessonRepository;
    private final ScheduleRepository scheduleRepository;
    private final GroupRepository groupRepository;
    private final SubjectRepository subjectRepository;

    @Override
    public List<AttendanceDTO> getAttendanceByLesson(Long lessonId) {
        return attendanceRepository.findByLessonId(lessonId)
                .stream()
                .map(a -> {
                    Lesson lesson = a.getLesson();
                    Schedule schedule = lesson.getSchedule();
                    return AttendanceDTO.builder()
                            .id(a.getId())
                            .studentId(a.getStudent().getId())
                            .studentName(a.getStudent().getName())
                            .lessonId(lesson.getId())
                            .subjectName(schedule.getSubject().getName())
                            .present(a.isPresent())
                            .date(lesson.getDate())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<AttendanceDTO> getAttendanceByGroupAndSubjectAndDate(Long groupId, Long subjectId, LocalDate date) {
        try {
            System.out.println("=== getAttendanceByGroupAndSubjectAndDate ===");
            System.out.println("groupId: " + groupId + ", subjectId: " + subjectId + ", date: " + date);

            // Получаем расписание (теперь используем findAllByGroupIdAndSubjectId)
            List<Schedule> schedules = scheduleRepository.findByGroupIdAndSubjectId(groupId, subjectId);

            if (schedules.isEmpty()) {
                System.out.println("No schedules found");
                // Возвращаем всех студентов с present=false
                List<Student> students = studentRepository.findByGroupId(groupId);
                List<AttendanceDTO> result = new ArrayList<>();
                for (Student student : students) {
                    result.add(AttendanceDTO.builder()
                            .studentId(student.getId())
                            .studentName(student.getName())
                            .present(false)
                            .date(date)
                            .build());
                }
                return result;
            }

            System.out.println("Found " + schedules.size() + " schedules");

            // Ищем Lesson для каждого расписания
            List<AttendanceDTO> result = new ArrayList<>();

            for (Schedule schedule : schedules) {
                Optional<Lesson> lessonOpt = lessonRepository.findByScheduleIdAndDate(schedule.getId(), date);

                if (lessonOpt.isPresent()) {
                    Lesson lesson = lessonOpt.get();
                    System.out.println("Found lesson for schedule " + schedule.getId());

                    // Получаем отметки
                    List<Attendance> attendances = attendanceRepository.findByLessonId(lesson.getId());
                    for (Attendance a : attendances) {
                        result.add(AttendanceDTO.builder()
                                .studentId(a.getStudent().getId())
                                .studentName(a.getStudent().getName())
                                .present(a.isPresent())
                                .date(date)
                                .build());
                    }
                }
            }

            // Если нет отметок, возвращаем всех студентов с present=false
            if (result.isEmpty()) {
                List<Student> students = studentRepository.findByGroupId(groupId);
                for (Student student : students) {
                    result.add(AttendanceDTO.builder()
                            .studentId(student.getId())
                            .studentName(student.getName())
                            .present(false)
                            .date(date)
                            .build());
                }
            }

            return result;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to get attendance: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public AttendanceDTO saveAttendance(AttendanceDTO dto) {
        System.out.println("Saving attendance: " + dto);

        Student student = studentRepository.findById(dto.getStudentId())
                .orElseThrow(() -> new RuntimeException("Student not found: " + dto.getStudentId()));

        // ИСПРАВЛЕНО: findByGroupIdAndSubjectId возвращает List, а не Optional
        List<Schedule> schedules = scheduleRepository.findByGroupIdAndSubjectId(dto.getGroupId(), dto.getSubjectId());

        if (schedules.isEmpty()) {
            throw new RuntimeException("Schedule not found for group " + dto.getGroupId() + " and subject " + dto.getSubjectId());
        }

        Schedule schedule = schedules.get(0); // берем первый

        Lesson lesson = findOrCreateLesson(schedule.getId(), dto.getDate());

        Attendance attendance = attendanceRepository
                .findByStudentIdAndLessonId(student.getId(), lesson.getId())
                .orElse(new Attendance());

        attendance.setStudent(student);
        attendance.setLesson(lesson);
        attendance.setPresent(dto.isPresent());

        Attendance saved = attendanceRepository.save(attendance);
        System.out.println("Saved attendance with id: " + saved.getId());

        return AttendanceDTO.builder()
                .id(saved.getId())
                .studentId(saved.getStudent().getId())
                .studentName(saved.getStudent().getName())
                .lessonId(lesson.getId())
                .subjectName(schedule.getSubject().getName())
                .present(saved.isPresent())
                .date(lesson.getDate())
                .build();
    }

//

    @Override
    @Transactional
    public void saveAll(List<AttendanceDTO> list) {
        try {
            System.out.println("=== ATTENDANCE SERVICE SAVEALL ===");

            if (list == null || list.isEmpty()) {
                System.out.println("Empty list");
                return;
            }

            AttendanceDTO first = list.get(0);
            Long groupId = first.getGroupId();
            Long subjectId = first.getSubjectId();
            LocalDate date = first.getDate();

            System.out.println("Looking for schedule with groupId=" + groupId + ", subjectId=" + subjectId);

            // ИСПРАВЛЕНО: findByGroupIdAndSubjectId возвращает List
            List<Schedule> schedules = scheduleRepository.findByGroupIdAndSubjectId(groupId, subjectId);

            if (schedules.isEmpty()) {
                System.err.println("Schedule NOT FOUND for group " + groupId + " and subject " + subjectId);
                throw new RuntimeException("Schedule not found. Please create schedule first.");
            }

            Schedule schedule = schedules.get(0); // берем первый
            System.out.println("Schedule found: id=" + schedule.getId());

            // Проверяем существование Lesson
            Optional<Lesson> lessonOpt = lessonRepository.findByScheduleIdAndDate(schedule.getId(), date);
            Lesson lesson;

            if (lessonOpt.isEmpty()) {
                System.out.println("Lesson not found, creating new one...");
                lesson = new Lesson();
                lesson.setSchedule(schedule);
                lesson.setDate(date);
                lesson.setStatus(Lesson.LessonStatus.PLANNED);
                lesson = lessonRepository.save(lesson);
                System.out.println("Created new lesson with id=" + lesson.getId());
            } else {
                lesson = lessonOpt.get();
                System.out.println("Lesson found: id=" + lesson.getId());
            }

            // Сохраняем посещаемость
            for (AttendanceDTO dto : list) {
                System.out.println("Processing studentId=" + dto.getStudentId());

                Student student = studentRepository.findById(dto.getStudentId())
                        .orElseThrow(() -> new RuntimeException("Student not found: " + dto.getStudentId()));

                Optional<Attendance> existingAttendance = attendanceRepository
                        .findByStudentIdAndLessonId(student.getId(), lesson.getId());

                Attendance attendance;
                if (existingAttendance.isPresent()) {
                    attendance = existingAttendance.get();
                    System.out.println("Updating existing attendance id=" + attendance.getId());
                } else {
                    attendance = new Attendance();
                    System.out.println("Creating new attendance");
                }

                attendance.setStudent(student);
                attendance.setLesson(lesson);
                attendance.setPresent(dto.isPresent());

                attendanceRepository.save(attendance);
                System.out.println("Saved attendance for student " + dto.getStudentId());
            }

            System.out.println("All attendance records saved successfully!");

        } catch (Exception e) {
            System.err.println("ERROR in saveAll: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to save attendance: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public Lesson findOrCreateLesson(Long scheduleId, LocalDate date) {
        return lessonRepository.findByScheduleIdAndDate(scheduleId, date)
                .orElseGet(() -> {
                    Schedule schedule = scheduleRepository.findById(scheduleId)
                            .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));

                    Lesson newLesson = new Lesson();
                    newLesson.setSchedule(schedule);
                    newLesson.setDate(date);
                    newLesson.setStatus(Lesson.LessonStatus.PLANNED);

                    System.out.println("Creating new lesson for schedule " + scheduleId + ", date " + date);
                    return lessonRepository.save(newLesson);
                });
    }
}