--
-- PostgreSQL database dump
--

-- Nidaa base schema reconstructed from the PostgreSQL 17 development catalog.
-- It intentionally stops before V2 additions. The base volunteer_id and
-- assigned_by columns are nullable so V2 legacy conversion and V7's explicit
-- role/source constraints can be applied without an invalid intermediate shape.

\restrict QoR2d30UpFVnxt7XVoQKyaPjrpXOczCoSDgBaqlB6S3bGuBCfvawQZ3Mavyh28P

-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: consultation_format; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.consultation_format AS ENUM (
    'CHAT',
    'AUDIO',
    'VIDEO'
);


--
-- Name: help_request_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.help_request_status AS ENUM (
    'PENDING',
    'ASSIGNED',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED'
);


--
-- Name: help_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.help_type AS ENUM (
    'MEDICAL',
    'FOOD',
    'CLOTHING',
    'SHELTER',
    'WATER',
    'OTHER'
);


--
-- Name: material_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.material_type AS ENUM (
    'ARTICLE',
    'VIDEO',
    'AUDIO',
    'INSTRUCTION'
);


--
-- Name: notification_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.notification_status AS ENUM (
    'PENDING',
    'SENT',
    'READ',
    'FAILED'
);


--
-- Name: notification_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.notification_type AS ENUM (
    'EMAIL',
    'SMS',
    'PUSH',
    'IN_APP'
);


--
-- Name: psychological_category; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.psychological_category AS ENUM (
    'ANXIETY',
    'DEPRESSION',
    'PTSD',
    'GRIEF',
    'VIOLENCE',
    'CHILD',
    'CRISIS',
    'OTHER'
);


--
-- Name: support_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.support_type AS ENUM (
    'INDIVIDUAL',
    'GROUP',
    'CRISIS'
);


--
-- Name: urgency_level; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.urgency_level AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH',
    'CRITICAL'
);


--
-- Name: user_role; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.user_role AS ENUM (
    'BENEFICIARY',
    'VOLUNTEER',
    'PSYCHOLOGIST',
    'ORGANIZATION',
    'ADMIN'
);


--
-- Name: calculate_priority_score(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.calculate_priority_score() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    base_score INTEGER := 0;
BEGIN
    CASE NEW.urgency_level
        WHEN 'CRITICAL' THEN base_score := base_score + 50;
        WHEN 'HIGH' THEN base_score := base_score + 35;
        WHEN 'MEDIUM' THEN base_score := base_score + 20;
        WHEN 'LOW' THEN base_score := base_score + 5;
    END CASE;
    
    IF NEW.has_children THEN base_score := base_score + 15; END IF;
    IF NEW.has_elderly THEN base_score := base_score + 15; END IF;
    IF NEW.has_disabled THEN base_score := base_score + 15; END IF;
    
    base_score := base_score + LEAST(NEW.people_count, 10);
    
    base_score := base_score + LEAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - NEW.created_at)) / 3600, 20);
    
    NEW.priority_score := LEAST(base_score, 100);
    RETURN NEW;
END;
$$;


--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: activity_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activity_logs (
    log_id bigint NOT NULL,
    user_id bigint,
    action character varying(100) NOT NULL,
    entity_type character varying(50),
    entity_id bigint,
    details jsonb,
    ip_address inet,
    user_agent text,
    "timestamp" timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: activity_logs_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.activity_logs_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: activity_logs_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.activity_logs_log_id_seq OWNED BY public.activity_logs.log_id;


--
-- Name: assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assignments (
    assignment_id bigint NOT NULL,
    request_id bigint NOT NULL,
    volunteer_id bigint,
    organization_id bigint,
    assigned_by bigint,
    assigned_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    notes text,
    completed_at timestamp without time zone
);


--
-- Name: assignments_assignment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.assignments_assignment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: assignments_assignment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.assignments_assignment_id_seq OWNED BY public.assignments.assignment_id;


--
-- Name: consultations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consultations (
    consultation_id bigint NOT NULL,
    psychological_request_id bigint NOT NULL,
    psychologist_id bigint NOT NULL,
    beneficiary_id bigint NOT NULL,
    format public.consultation_format NOT NULL,
    started_at timestamp without time zone NOT NULL,
    ended_at timestamp without time zone,
    duration_minutes integer,
    topics_discussed text[],
    recommendations text,
    feedback_from_beneficiary text,
    rating integer,
    notes_for_psychologist text,
    is_crisis boolean DEFAULT false,
    chat_session_id uuid,
    CONSTRAINT duration_positive CHECK ((duration_minutes >= 0)),
    CONSTRAINT rating_range CHECK (((rating >= 1) AND (rating <= 5)))
);


--
-- Name: consultations_consultation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.consultations_consultation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: consultations_consultation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.consultations_consultation_id_seq OWNED BY public.consultations.consultation_id;


--
-- Name: group_participants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_participants (
    participant_id bigint NOT NULL,
    session_id bigint NOT NULL,
    beneficiary_id bigint NOT NULL,
    joined_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    attended boolean DEFAULT false,
    feedback text,
    rating integer
);


--
-- Name: group_participants_participant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.group_participants_participant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: group_participants_participant_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.group_participants_participant_id_seq OWNED BY public.group_participants.participant_id;


--
-- Name: group_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_sessions (
    session_id bigint NOT NULL,
    psychologist_id bigint NOT NULL,
    title character varying(255) NOT NULL,
    description text,
    category public.psychological_category NOT NULL,
    max_participants integer DEFAULT 10 NOT NULL,
    current_participants integer DEFAULT 0,
    format public.consultation_format DEFAULT 'VIDEO'::public.consultation_format,
    scheduled_at timestamp without time zone NOT NULL,
    duration_minutes integer DEFAULT 60 NOT NULL,
    is_recurring boolean DEFAULT false,
    recurrence_pattern jsonb,
    status character varying(20) DEFAULT 'SCHEDULED'::character varying,
    meeting_link text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT duration_positive CHECK ((duration_minutes > 0)),
    CONSTRAINT max_participants_positive CHECK ((max_participants > 0))
);


--
-- Name: group_sessions_session_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.group_sessions_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: group_sessions_session_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.group_sessions_session_id_seq OWNED BY public.group_sessions.session_id;


--
-- Name: help_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.help_requests (
    request_id bigint NOT NULL,
    beneficiary_id bigint NOT NULL,
    title character varying(255) NOT NULL,
    description text NOT NULL,
    help_type public.help_type NOT NULL,
    urgency_level public.urgency_level DEFAULT 'MEDIUM'::public.urgency_level NOT NULL,
    priority_score integer DEFAULT 0,
    people_count integer DEFAULT 1,
    has_children boolean DEFAULT false,
    has_elderly boolean DEFAULT false,
    has_disabled boolean DEFAULT false,
    address text,
    latitude numeric(10,8),
    longitude numeric(11,8),
    status public.help_request_status DEFAULT 'PENDING'::public.help_request_status,
    assigned_volunteer_id bigint,
    assigned_organization_id bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    cancellation_reason text,
    CONSTRAINT people_count_positive CHECK ((people_count > 0)),
    CONSTRAINT priority_score_range CHECK (((priority_score >= 0) AND (priority_score <= 100)))
);


--
-- Name: help_requests_request_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.help_requests_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: help_requests_request_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.help_requests_request_id_seq OWNED BY public.help_requests.request_id;


--
-- Name: locations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.locations (
    location_id bigint NOT NULL,
    country character varying(100),
    region character varying(100),
    city character varying(100),
    district character varying(100),
    latitude numeric(10,8),
    longitude numeric(11,8),
    place_name character varying(255),
    population_estimate integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: locations_location_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.locations_location_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: locations_location_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.locations_location_id_seq OWNED BY public.locations.location_id;


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.messages (
    message_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    receiver_id bigint NOT NULL,
    help_request_id bigint,
    psychological_request_id bigint,
    content text NOT NULL,
    is_read boolean DEFAULT false,
    read_at timestamp without time zone,
    sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    is_encrypted boolean DEFAULT false
);


--
-- Name: messages_message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.messages_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: messages_message_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.messages_message_id_seq OWNED BY public.messages.message_id;


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    notification_id bigint NOT NULL,
    user_id bigint NOT NULL,
    type public.notification_type NOT NULL,
    title character varying(255) NOT NULL,
    content text NOT NULL,
    reference_id bigint,
    reference_type character varying(50),
    status public.notification_status DEFAULT 'PENDING'::public.notification_status,
    sent_at timestamp without time zone,
    read_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: notifications_notification_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notifications_notification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications_notification_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.notifications_notification_id_seq OWNED BY public.notifications.notification_id;


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    organization_id bigint NOT NULL,
    user_id bigint NOT NULL,
    official_name character varying(255) NOT NULL,
    registration_number character varying(100) NOT NULL,
    tax_id character varying(50),
    website character varying(255),
    mission_statement text,
    operational_areas text,
    verified_at timestamp without time zone,
    verified_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    is_verified boolean DEFAULT false NOT NULL
);


--
-- Name: organizations_organization_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.organizations_organization_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: organizations_organization_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.organizations_organization_id_seq OWNED BY public.organizations.organization_id;


--
-- Name: password_reset_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_tokens (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    code character varying(20) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: password_reset_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.password_reset_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: password_reset_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.password_reset_tokens_id_seq OWNED BY public.password_reset_tokens.id;


--
-- Name: pending_registrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pending_registrations (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    full_name character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    phone character varying(50),
    role character varying(50) NOT NULL,
    code character varying(20) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: pending_registrations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pending_registrations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pending_registrations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pending_registrations_id_seq OWNED BY public.pending_registrations.id;


--
-- Name: profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profiles (
    profile_id bigint NOT NULL,
    user_id bigint NOT NULL,
    avatar_url text,
    bio text,
    address text,
    latitude numeric(10,8),
    longitude numeric(11,8),
    preferred_language character varying(10) DEFAULT 'ru'::character varying,
    notification_settings jsonb DEFAULT '{"sms": false, "push": true, "email": true}'::jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: profiles_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.profiles_profile_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: profiles_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.profiles_profile_id_seq OWNED BY public.profiles.profile_id;


--
-- Name: psychological_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.psychological_requests (
    request_id bigint NOT NULL,
    beneficiary_id bigint NOT NULL,
    assigned_psychologist_id bigint,
    support_type public.support_type DEFAULT 'INDIVIDUAL'::public.support_type NOT NULL,
    category public.psychological_category NOT NULL,
    urgency_level public.urgency_level DEFAULT 'MEDIUM'::public.urgency_level NOT NULL,
    preferred_format public.consultation_format DEFAULT 'CHAT'::public.consultation_format,
    preferred_time timestamp without time zone,
    description text,
    is_anonymous boolean DEFAULT false,
    status public.help_request_status DEFAULT 'PENDING'::public.help_request_status,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp without time zone,
    is_crisis boolean DEFAULT false,
    crisis_detected_at timestamp without time zone
);


--
-- Name: psychological_requests_request_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.psychological_requests_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: psychological_requests_request_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.psychological_requests_request_id_seq OWNED BY public.psychological_requests.request_id;


--
-- Name: psychologists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.psychologists (
    psychologist_id bigint NOT NULL,
    user_id bigint NOT NULL,
    specialization public.psychological_category[] NOT NULL,
    education jsonb,
    certificates text,
    experience_years integer DEFAULT 0,
    languages text DEFAULT ARRAY['ru'::text],
    consultation_count integer DEFAULT 0,
    rating numeric(3,2) DEFAULT 5.0,
    is_verified boolean DEFAULT false,
    verified_at timestamp without time zone,
    verified_by bigint,
    available_schedule jsonb,
    is_on_duty boolean DEFAULT false,
    hourly_rate integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT experience_years_positive CHECK ((experience_years >= 0)),
    CONSTRAINT rating_range CHECK (((rating >= (1)::numeric) AND (rating <= (5)::numeric)))
);


--
-- Name: psychologists_psychologist_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.psychologists_psychologist_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: psychologists_psychologist_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.psychologists_psychologist_id_seq OWNED BY public.psychologists.psychologist_id;


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    token character varying(100) NOT NULL,
    email character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;


--
-- Name: reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reports (
    report_id bigint NOT NULL,
    assignment_id bigint NOT NULL,
    volunteer_id bigint NOT NULL,
    description text NOT NULL,
    photos text,
    feedback_from_beneficiary text,
    beneficiary_rating integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rating_range CHECK (((beneficiary_rating >= 1) AND (beneficiary_rating <= 5)))
);


--
-- Name: reports_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.reports_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: reports_report_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.reports_report_id_seq OWNED BY public.reports.report_id;


--
-- Name: request_media; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.request_media (
    media_id bigint NOT NULL,
    request_id bigint NOT NULL,
    media_url text NOT NULL,
    media_type character varying(50) NOT NULL,
    file_size bigint,
    mime_type character varying(100),
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: request_media_media_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.request_media_media_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: request_media_media_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.request_media_media_id_seq OWNED BY public.request_media.media_id;


--
-- Name: self_help_materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.self_help_materials (
    material_id bigint NOT NULL,
    author_id bigint,
    title character varying(255) NOT NULL,
    description text,
    content_type public.material_type NOT NULL,
    category public.psychological_category,
    tags text[],
    content_url text NOT NULL,
    thumbnail_url text,
    duration_seconds integer,
    views_count integer DEFAULT 0,
    helpful_count integer DEFAULT 0,
    is_published boolean DEFAULT true,
    language character varying(10) DEFAULT 'ru'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: self_help_materials_material_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.self_help_materials_material_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: self_help_materials_material_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.self_help_materials_material_id_seq OWNED BY public.self_help_materials.material_id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    user_id bigint NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    phone character varying(20) NOT NULL,
    full_name character varying(255) NOT NULL,
    role character varying DEFAULT 'BENEFICIARY'::public.user_role NOT NULL,
    is_verified boolean DEFAULT false,
    is_active boolean DEFAULT true,
    is_locked boolean DEFAULT false,
    last_login timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamp without time zone,
    CONSTRAINT email_valid CHECK (((email)::text ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'::text))
);


--
-- Name: users_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_user_id_seq OWNED BY public.users.user_id;


--
-- Name: volunteers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.volunteers (
    volunteer_id bigint NOT NULL,
    user_id bigint NOT NULL,
    organization_id bigint,
    skills text,
    availability jsonb,
    total_completed_requests integer DEFAULT 0,
    rating numeric(3,2) DEFAULT 5.0,
    joined_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    is_available boolean DEFAULT true,
    latitude numeric,
    longitude numeric,
    CONSTRAINT rating_range CHECK (((rating >= (1)::numeric) AND (rating <= (5)::numeric)))
);


--
-- Name: volunteers_volunteer_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.volunteers_volunteer_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: volunteers_volunteer_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.volunteers_volunteer_id_seq OWNED BY public.volunteers.volunteer_id;


--
-- Name: activity_logs log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_logs ALTER COLUMN log_id SET DEFAULT nextval('public.activity_logs_log_id_seq'::regclass);


--
-- Name: assignments assignment_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments ALTER COLUMN assignment_id SET DEFAULT nextval('public.assignments_assignment_id_seq'::regclass);


--
-- Name: consultations consultation_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations ALTER COLUMN consultation_id SET DEFAULT nextval('public.consultations_consultation_id_seq'::regclass);


--
-- Name: group_participants participant_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_participants ALTER COLUMN participant_id SET DEFAULT nextval('public.group_participants_participant_id_seq'::regclass);


--
-- Name: group_sessions session_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_sessions ALTER COLUMN session_id SET DEFAULT nextval('public.group_sessions_session_id_seq'::regclass);


--
-- Name: help_requests request_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_requests ALTER COLUMN request_id SET DEFAULT nextval('public.help_requests_request_id_seq'::regclass);


--
-- Name: locations location_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locations ALTER COLUMN location_id SET DEFAULT nextval('public.locations_location_id_seq'::regclass);


--
-- Name: messages message_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages ALTER COLUMN message_id SET DEFAULT nextval('public.messages_message_id_seq'::regclass);


--
-- Name: notifications notification_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications ALTER COLUMN notification_id SET DEFAULT nextval('public.notifications_notification_id_seq'::regclass);


--
-- Name: organizations organization_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations ALTER COLUMN organization_id SET DEFAULT nextval('public.organizations_organization_id_seq'::regclass);


--
-- Name: password_reset_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens ALTER COLUMN id SET DEFAULT nextval('public.password_reset_tokens_id_seq'::regclass);


--
-- Name: pending_registrations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_registrations ALTER COLUMN id SET DEFAULT nextval('public.pending_registrations_id_seq'::regclass);


--
-- Name: profiles profile_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles ALTER COLUMN profile_id SET DEFAULT nextval('public.profiles_profile_id_seq'::regclass);


--
-- Name: psychological_requests request_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychological_requests ALTER COLUMN request_id SET DEFAULT nextval('public.psychological_requests_request_id_seq'::regclass);


--
-- Name: psychologists psychologist_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychologists ALTER COLUMN psychologist_id SET DEFAULT nextval('public.psychologists_psychologist_id_seq'::regclass);


--
-- Name: refresh_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('public.refresh_tokens_id_seq'::regclass);


--
-- Name: reports report_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reports ALTER COLUMN report_id SET DEFAULT nextval('public.reports_report_id_seq'::regclass);


--
-- Name: request_media media_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.request_media ALTER COLUMN media_id SET DEFAULT nextval('public.request_media_media_id_seq'::regclass);


--
-- Name: self_help_materials material_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.self_help_materials ALTER COLUMN material_id SET DEFAULT nextval('public.self_help_materials_material_id_seq'::regclass);


--
-- Name: users user_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN user_id SET DEFAULT nextval('public.users_user_id_seq'::regclass);


--
-- Name: volunteers volunteer_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers ALTER COLUMN volunteer_id SET DEFAULT nextval('public.volunteers_volunteer_id_seq'::regclass);


--
-- Name: activity_logs activity_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_logs
    ADD CONSTRAINT activity_logs_pkey PRIMARY KEY (log_id);


--
-- Name: assignments assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_pkey PRIMARY KEY (assignment_id);


--
-- Name: consultations consultations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_pkey PRIMARY KEY (consultation_id);


--
-- Name: group_participants group_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_participants
    ADD CONSTRAINT group_participants_pkey PRIMARY KEY (participant_id);


--
-- Name: group_participants group_participants_session_id_beneficiary_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_participants
    ADD CONSTRAINT group_participants_session_id_beneficiary_id_key UNIQUE (session_id, beneficiary_id);


--
-- Name: group_sessions group_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_sessions
    ADD CONSTRAINT group_sessions_pkey PRIMARY KEY (session_id);


--
-- Name: help_requests help_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_requests
    ADD CONSTRAINT help_requests_pkey PRIMARY KEY (request_id);


--
-- Name: locations locations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locations
    ADD CONSTRAINT locations_pkey PRIMARY KEY (location_id);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (message_id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (notification_id);


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (organization_id);


--
-- Name: organizations organizations_registration_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_registration_number_key UNIQUE (registration_number);


--
-- Name: organizations organizations_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_user_id_key UNIQUE (user_id);


--
-- Name: password_reset_tokens password_reset_tokens_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_email_key UNIQUE (email);


--
-- Name: password_reset_tokens password_reset_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);


--
-- Name: pending_registrations pending_registrations_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_registrations
    ADD CONSTRAINT pending_registrations_email_key UNIQUE (email);


--
-- Name: pending_registrations pending_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_registrations
    ADD CONSTRAINT pending_registrations_pkey PRIMARY KEY (id);


--
-- Name: profiles profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_pkey PRIMARY KEY (profile_id);


--
-- Name: profiles profiles_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_user_id_key UNIQUE (user_id);


--
-- Name: psychological_requests psychological_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychological_requests
    ADD CONSTRAINT psychological_requests_pkey PRIMARY KEY (request_id);


--
-- Name: psychologists psychologists_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychologists
    ADD CONSTRAINT psychologists_pkey PRIMARY KEY (psychologist_id);


--
-- Name: psychologists psychologists_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychologists
    ADD CONSTRAINT psychologists_user_id_key UNIQUE (user_id);


--
-- Name: refresh_tokens refresh_tokens_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_email_key UNIQUE (email);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_key UNIQUE (token);


--
-- Name: reports reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_pkey PRIMARY KEY (report_id);


--
-- Name: request_media request_media_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.request_media
    ADD CONSTRAINT request_media_pkey PRIMARY KEY (media_id);


--
-- Name: self_help_materials self_help_materials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.self_help_materials
    ADD CONSTRAINT self_help_materials_pkey PRIMARY KEY (material_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_phone_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_phone_key UNIQUE (phone);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


--
-- Name: volunteers volunteers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT volunteers_pkey PRIMARY KEY (volunteer_id);


--
-- Name: volunteers volunteers_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT volunteers_user_id_key UNIQUE (user_id);


--
-- Name: idx_assignments_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assignments_request ON public.assignments USING btree (request_id);


--
-- Name: idx_assignments_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assignments_status ON public.assignments USING btree (status);


--
-- Name: idx_assignments_volunteer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assignments_volunteer ON public.assignments USING btree (volunteer_id);


--
-- Name: idx_assignments_volunteer_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assignments_volunteer_status ON public.assignments USING btree (volunteer_id, status);


--
-- Name: idx_consultations_beneficiary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_beneficiary ON public.consultations USING btree (beneficiary_id);


--
-- Name: idx_consultations_psych_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_psych_request ON public.consultations USING btree (psychological_request_id);


--
-- Name: idx_consultations_psychologist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_psychologist ON public.consultations USING btree (psychologist_id);


--
-- Name: idx_consultations_started_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consultations_started_at ON public.consultations USING btree (started_at);


--
-- Name: idx_group_participants_beneficiary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_participants_beneficiary ON public.group_participants USING btree (beneficiary_id);


--
-- Name: idx_group_participants_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_participants_session ON public.group_participants USING btree (session_id);


--
-- Name: idx_group_sessions_psychologist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_sessions_psychologist ON public.group_sessions USING btree (psychologist_id);


--
-- Name: idx_group_sessions_scheduled_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_sessions_scheduled_at ON public.group_sessions USING btree (scheduled_at);


--
-- Name: idx_group_sessions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_sessions_status ON public.group_sessions USING btree (status);


--
-- Name: idx_help_requests_beneficiary_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_beneficiary_id ON public.help_requests USING btree (beneficiary_id);


--
-- Name: idx_help_requests_coordinates; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_coordinates ON public.help_requests USING btree (latitude, longitude);


--
-- Name: idx_help_requests_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_created_at ON public.help_requests USING btree (created_at);


--
-- Name: idx_help_requests_help_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_help_type ON public.help_requests USING btree (help_type);


--
-- Name: idx_help_requests_priority; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_priority ON public.help_requests USING btree (priority_score DESC);


--
-- Name: idx_help_requests_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_status ON public.help_requests USING btree (status);


--
-- Name: idx_help_requests_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_status_created ON public.help_requests USING btree (status, created_at DESC);


--
-- Name: idx_help_requests_status_priority; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_status_priority ON public.help_requests USING btree (status, priority_score DESC);


--
-- Name: idx_help_requests_type_urgency; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_type_urgency ON public.help_requests USING btree (help_type, urgency_level);


--
-- Name: idx_help_requests_type_urgency_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_type_urgency_status ON public.help_requests USING btree (help_type, urgency_level, status);


--
-- Name: idx_help_requests_urgency; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_help_requests_urgency ON public.help_requests USING btree (urgency_level);


--
-- Name: idx_locations_city; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_locations_city ON public.locations USING btree (city);


--
-- Name: idx_locations_coordinates; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_locations_coordinates ON public.locations USING btree (latitude, longitude);


--
-- Name: idx_logs_action; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_logs_action ON public.activity_logs USING btree (action);


--
-- Name: idx_logs_timestamp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_logs_timestamp ON public.activity_logs USING btree ("timestamp");


--
-- Name: idx_logs_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_logs_user ON public.activity_logs USING btree (user_id);


--
-- Name: idx_materials_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_category ON public.self_help_materials USING btree (category);


--
-- Name: idx_materials_content_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_content_type ON public.self_help_materials USING btree (content_type);


--
-- Name: idx_materials_is_published; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_is_published ON public.self_help_materials USING btree (is_published);


--
-- Name: idx_materials_tags; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_materials_tags ON public.self_help_materials USING gin (tags);


--
-- Name: idx_messages_help_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_help_request ON public.messages USING btree (help_request_id);


--
-- Name: idx_messages_psych_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_psych_request ON public.messages USING btree (psychological_request_id);


--
-- Name: idx_messages_receiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_receiver ON public.messages USING btree (receiver_id);


--
-- Name: idx_messages_sender; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_sender ON public.messages USING btree (sender_id);


--
-- Name: idx_messages_sender_receiver; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_sender_receiver ON public.messages USING btree (sender_id, receiver_id, sent_at DESC);


--
-- Name: idx_messages_sent_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_sent_at ON public.messages USING btree (sent_at);


--
-- Name: idx_messages_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_messages_unread ON public.messages USING btree (receiver_id, is_read, sent_at DESC);


--
-- Name: idx_notifications_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_created_at ON public.notifications USING btree (created_at);


--
-- Name: idx_notifications_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_status ON public.notifications USING btree (status);


--
-- Name: idx_notifications_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_user ON public.notifications USING btree (user_id);


--
-- Name: idx_notifications_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_user_status ON public.notifications USING btree (user_id, status, created_at DESC);


--
-- Name: idx_organizations_registration_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_registration_number ON public.organizations USING btree (registration_number);


--
-- Name: idx_organizations_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_organizations_user_id ON public.organizations USING btree (user_id);


--
-- Name: idx_pending_reg_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pending_reg_email ON public.pending_registrations USING btree (email);


--
-- Name: idx_pending_reg_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pending_reg_expires_at ON public.pending_registrations USING btree (expires_at);


--
-- Name: idx_profiles_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profiles_user_id ON public.profiles USING btree (user_id);


--
-- Name: idx_psych_requests_beneficiary_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psych_requests_beneficiary_id ON public.psychological_requests USING btree (beneficiary_id);


--
-- Name: idx_psych_requests_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psych_requests_category ON public.psychological_requests USING btree (category);


--
-- Name: idx_psych_requests_is_crisis; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psych_requests_is_crisis ON public.psychological_requests USING btree (is_crisis);


--
-- Name: idx_psych_requests_psychologist_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psych_requests_psychologist_id ON public.psychological_requests USING btree (assigned_psychologist_id);


--
-- Name: idx_psych_requests_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psych_requests_status ON public.psychological_requests USING btree (status);


--
-- Name: idx_psychologists_is_verified; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psychologists_is_verified ON public.psychologists USING btree (is_verified);


--
-- Name: idx_psychologists_specialization; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psychologists_specialization ON public.psychologists USING gin (specialization);


--
-- Name: idx_psychologists_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_psychologists_user_id ON public.psychologists USING btree (user_id);


--
-- Name: idx_refresh_tokens_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_email ON public.refresh_tokens USING btree (email);


--
-- Name: idx_refresh_tokens_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_token ON public.refresh_tokens USING btree (token);


--
-- Name: idx_reports_assignment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_assignment ON public.reports USING btree (assignment_id);


--
-- Name: idx_reports_volunteer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_volunteer ON public.reports USING btree (volunteer_id);


--
-- Name: idx_request_media_request_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_request_media_request_id ON public.request_media USING btree (request_id);


--
-- Name: idx_users_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_created_at ON public.users USING btree (created_at);


--
-- Name: idx_users_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_email ON public.users USING btree (email);


--
-- Name: idx_users_email_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_email_lower ON public.users USING btree (lower((email)::text));


--
-- Name: idx_users_is_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_is_active ON public.users USING btree (is_active);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role);


--
-- Name: idx_users_role_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role_active ON public.users USING btree (role, is_active);


--
-- Name: idx_volunteers_organization_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_volunteers_organization_id ON public.volunteers USING btree (organization_id);


--
-- Name: idx_volunteers_rating; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_volunteers_rating ON public.volunteers USING btree (rating);


--
-- Name: idx_volunteers_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_volunteers_user_id ON public.volunteers USING btree (user_id);


--
-- Name: help_requests calculate_priority_before_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER calculate_priority_before_insert BEFORE INSERT ON public.help_requests FOR EACH ROW EXECUTE FUNCTION public.calculate_priority_score();


--
-- Name: help_requests calculate_priority_before_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER calculate_priority_before_update BEFORE UPDATE OF urgency_level, has_children, has_elderly, has_disabled, people_count ON public.help_requests FOR EACH ROW EXECUTE FUNCTION public.calculate_priority_score();


--
-- Name: help_requests update_help_requests_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_help_requests_updated_at BEFORE UPDATE ON public.help_requests FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: profiles update_profiles_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_profiles_updated_at BEFORE UPDATE ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: psychological_requests update_psychological_requests_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_psychological_requests_updated_at BEFORE UPDATE ON public.psychological_requests FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: psychologists update_psychologists_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_psychologists_updated_at BEFORE UPDATE ON public.psychologists FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: self_help_materials update_self_help_materials_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_self_help_materials_updated_at BEFORE UPDATE ON public.self_help_materials FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: users update_users_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: activity_logs activity_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_logs
    ADD CONSTRAINT activity_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE SET NULL;


--
-- Name: assignments assignments_assigned_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_assigned_by_fkey FOREIGN KEY (assigned_by) REFERENCES public.users(user_id);


--
-- Name: assignments assignments_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organizations(organization_id);


--
-- Name: assignments assignments_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_request_id_fkey FOREIGN KEY (request_id) REFERENCES public.help_requests(request_id) ON DELETE CASCADE;


--
-- Name: assignments assignments_volunteer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_volunteer_id_fkey FOREIGN KEY (volunteer_id) REFERENCES public.volunteers(volunteer_id);


--
-- Name: consultations consultations_beneficiary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_beneficiary_id_fkey FOREIGN KEY (beneficiary_id) REFERENCES public.users(user_id);


--
-- Name: consultations consultations_psychological_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_psychological_request_id_fkey FOREIGN KEY (psychological_request_id) REFERENCES public.psychological_requests(request_id) ON DELETE CASCADE;


--
-- Name: consultations consultations_psychologist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultations
    ADD CONSTRAINT consultations_psychologist_id_fkey FOREIGN KEY (psychologist_id) REFERENCES public.psychologists(psychologist_id);


--
-- Name: volunteers fk_volunteers_organization; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT fk_volunteers_organization FOREIGN KEY (organization_id) REFERENCES public.organizations(organization_id) ON DELETE SET NULL;


--
-- Name: group_participants group_participants_beneficiary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_participants
    ADD CONSTRAINT group_participants_beneficiary_id_fkey FOREIGN KEY (beneficiary_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: group_participants group_participants_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_participants
    ADD CONSTRAINT group_participants_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.group_sessions(session_id) ON DELETE CASCADE;


--
-- Name: group_sessions group_sessions_psychologist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_sessions
    ADD CONSTRAINT group_sessions_psychologist_id_fkey FOREIGN KEY (psychologist_id) REFERENCES public.psychologists(psychologist_id);


--
-- Name: help_requests help_requests_assigned_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_requests
    ADD CONSTRAINT help_requests_assigned_organization_id_fkey FOREIGN KEY (assigned_organization_id) REFERENCES public.organizations(organization_id);


--
-- Name: help_requests help_requests_assigned_volunteer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_requests
    ADD CONSTRAINT help_requests_assigned_volunteer_id_fkey FOREIGN KEY (assigned_volunteer_id) REFERENCES public.volunteers(volunteer_id);


--
-- Name: help_requests help_requests_beneficiary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.help_requests
    ADD CONSTRAINT help_requests_beneficiary_id_fkey FOREIGN KEY (beneficiary_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: messages messages_help_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_help_request_id_fkey FOREIGN KEY (help_request_id) REFERENCES public.help_requests(request_id) ON DELETE SET NULL;


--
-- Name: messages messages_psychological_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_psychological_request_id_fkey FOREIGN KEY (psychological_request_id) REFERENCES public.psychological_requests(request_id) ON DELETE SET NULL;


--
-- Name: messages messages_receiver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_receiver_id_fkey FOREIGN KEY (receiver_id) REFERENCES public.users(user_id);


--
-- Name: messages messages_sender_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(user_id);


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: organizations organizations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: organizations organizations_verified_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_verified_by_fkey FOREIGN KEY (verified_by) REFERENCES public.users(user_id);


--
-- Name: profiles profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: psychological_requests psychological_requests_assigned_psychologist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychological_requests
    ADD CONSTRAINT psychological_requests_assigned_psychologist_id_fkey FOREIGN KEY (assigned_psychologist_id) REFERENCES public.psychologists(psychologist_id) ON DELETE SET NULL;


--
-- Name: psychological_requests psychological_requests_beneficiary_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychological_requests
    ADD CONSTRAINT psychological_requests_beneficiary_id_fkey FOREIGN KEY (beneficiary_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: psychologists psychologists_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychologists
    ADD CONSTRAINT psychologists_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: psychologists psychologists_verified_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.psychologists
    ADD CONSTRAINT psychologists_verified_by_fkey FOREIGN KEY (verified_by) REFERENCES public.users(user_id);


--
-- Name: reports reports_assignment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_assignment_id_fkey FOREIGN KEY (assignment_id) REFERENCES public.assignments(assignment_id) ON DELETE CASCADE;


--
-- Name: reports reports_volunteer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_volunteer_id_fkey FOREIGN KEY (volunteer_id) REFERENCES public.volunteers(volunteer_id);


--
-- Name: request_media request_media_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.request_media
    ADD CONSTRAINT request_media_request_id_fkey FOREIGN KEY (request_id) REFERENCES public.help_requests(request_id) ON DELETE CASCADE;


--
-- Name: self_help_materials self_help_materials_author_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.self_help_materials
    ADD CONSTRAINT self_help_materials_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.psychologists(psychologist_id) ON DELETE SET NULL;


--
-- Name: volunteers volunteers_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT volunteers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict QoR2d30UpFVnxt7XVoQKyaPjrpXOczCoSDgBaqlB6S3bGuBCfvawQZ3Mavyh28P
